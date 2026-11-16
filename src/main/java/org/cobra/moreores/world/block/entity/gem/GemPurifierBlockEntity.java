package org.cobra.moreores.world.block.entity.gem;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.fluid.FluidStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.cobra.moreores.client.menu.GemPurifierMenu;
import org.cobra.moreores.recipe.GemPurifierRecipe;
import org.cobra.moreores.recipe.ModRecipeType;
import org.cobra.moreores.recipe.input.GemPurifyingRecipeInput;
import org.cobra.moreores.tags.ModItemTags;
import org.cobra.moreores.world.block.GemPurifierBlock;
import org.cobra.moreores.world.block.ModBlocks;
import org.cobra.moreores.world.block.entity.ModBlockEntityType;
import org.cobra.moreores.world.item.ModItems;
import org.cobra.moreores.world.item.util.GemCategory;
import org.cobra.moreores.world.item.util.impl.IGemstone;
import org.cobra.moreores.world.item.util.impl.PurificationGemstones;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

public class GemPurifierBlockEntity extends AbstractGemMachineBlockEntity {
    private long previousFluidMilestone = 0;
    private FluidState fluidState = FluidState.IDLE;
    
    protected final ContainerData containerData;
    
    public static final int INGREDIENT_SLOT = 0;
    public static final int RESULT_SLOT = 1;
    public static final int ENERGY_SLOT = 2;
    public static final int WATER_SLOT = 3;
    public static final int REDSTONE_SLOT = 4;

    public final FluidStacksResourceHandler fluidHandler = new FluidStacksResourceHandler(4, 10_000) {
        @Override
        public boolean isValid(int index, FluidResource resource) {
            return true;
        }

        @Override
        protected void onContentsChanged(int index, FluidStack previousContents) {
            setChanged();
            if(!getLevel().isClientSide()) {
                getLevel().sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), 3);
            }
        }
    };
    
    private int maxProgress = 384;
    private final RecipeManager.CachedCheck<GemPurifyingRecipeInput, GemPurifierRecipe> matchGetter = RecipeManager.createCheck(ModRecipeType.GEM_PURIFIER.get());

    public GemPurifierBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntityType.GEM_PURIFIER.get(), pos, blockState);
        this.gem = PurificationGemstones.EMPTY;
        this.containerData = new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case 0 -> GemPurifierBlockEntity.this.initialProgressTicks;
                    case 1 -> GemPurifierBlockEntity.this.maxProgress;
                    case 2 -> GemPurifierBlockEntity.this.redstone;
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                switch (index) {
                    case 0: GemPurifierBlockEntity.this.initialProgressTicks = value;
                    case 1: GemPurifierBlockEntity.this.maxProgress = value;
                    case 2: GemPurifierBlockEntity.this.redstone = value;
                }
            }

            @Override
            public int getCount() {
                return 3;
            }
        };
    }

    @Override
    protected boolean checkRecipe() {
        Optional<RecipeHolder<GemPurifierRecipe>> recipe = getCurrentRecipe();

        return recipe.isPresent() && hasRequiredEnergyAmount() && canInsertCountIntoResultSlot(recipe.get().value().result())
                && canInsertItemIntoResultSlot(recipe.get().value().result().getItem());
    }

    @Override
    public int inventoryStackSize() {
        return 16;
    }

    @Override
    public int energyCapacity() {
        return 10_000_000;
    }

    @Override
    public int maxEnergyInsertable() {
        return 192000;
    }

    @Override
    public int maxEnergyExtractable() {
        return 512000;
    }

    @Override
    public ItemResource ingredientStack() {
        return getStack(INGREDIENT_SLOT);
    }

    @Override
    public ItemResource resultStack() {
        return getStack(RESULT_SLOT);
    }

    @Override
    public ItemResource energyStack() {
        return getStack(ENERGY_SLOT);
    }

    public ItemResource fluidStack() {
        return getStack(WATER_SLOT);
    }

    public void drops() {
        SimpleContainer inv = new SimpleContainer(main.size());
        for (int i = 0; i < main.size(); i++) {
            ItemAccess itemAccess = ItemAccess.forHandlerIndex(main, i);
            inv.setItem(i, new ItemStack(itemAccess.getResource().getItem(), itemAccess.getAmount()));
        }
        if(level == null) {
            return;
        }
        Containers.dropContents(this.level, this.worldPosition, inv);
    }
    
    @Override
    public void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("GemType", PurificationGemstones.CODEC, gemstone());
        output.store("FluidState", FluidState.CODEC, fluidState);
        fluidHandler.serialize(output);
    }

    @Override
    public void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        gem = input.read("GemType", PurificationGemstones.CODEC).orElse(PurificationGemstones.EMPTY);
        fluidHandler.deserialize(input);
        fluidState = input.read("FluidState", FluidState.CODEC).orElse(FluidState.IDLE);
    }

    @Override
    public PurificationGemstones gemstone() {
        IGemstone gemstone = super.gemstone();
        if(gemstone instanceof PurificationGemstones c) {
            return c;
        }
        return PurificationGemstones.EMPTY;
    }
    
    @Override
    public Component getDisplayName() {
        return ModBlocks.GEM_PURIFIER_BLOCK.get().getName();
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new GemPurifierMenu(containerId, inventory, this, this.main, containerData);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    public FluidStack fluid() {
        return new FluidStack(fluidHandler.getResource(WATER_SLOT).getFluid(), fluidHandler.getAmountAsInt(WATER_SLOT));
    }
    
    public int fluidAmount() {
        return fluidHandler.getAmountFrom(fluid());
    }
    
    public void tick(Level level, BlockPos pos, BlockState state) {
        if (level.isClientSide()) {
            return;
        }

        redstoneTick++;

        IGemstone newGem = gemstone();

        if (newGem != this.gem) {
            setGemstone(newGem);

            level.sendBlockUpdated(pos, getBlockState(), getBlockState(), 3);
        }

        ItemResource main = redstoneStack(REDSTONE_SLOT);
        if((main.is(Items.REDSTONE) || level.hasNeighborSignal(pos)) && redstone <= maxRedstone) {
            redstone += 10;
            setChanged(level, pos, state);
        }

        modifyState();
        if(machineStatus == MachineStatus.RUNNING) {
            energyState = MachineStatus.EnergyState.EXTRACTING;
            if (canResultAccept() && checkRecipe() && hasRequiredEnergyAmount() && hasEnoughWater()) {
                this.increaseProgressTicks();
                if((!level.hasNeighborSignal(pos) || redstone > 0) && redstoneTick >= 20) {
                    redstone--;
                    redstoneTick = 0;
                }
                this.useEnergy();
                this.consumeWater();
                if (hasPurificationFinished()) {
                    this.getPurifiedGemstone();
                    this.clearProgress();
                }
                setChanged(level, pos, state);
            } else {
                this.clearProgress();
                this.machineStatus = MachineStatus.IDLE;
                setChanged(level, pos, state);
            }
        } else if (machineStatus.isPaused()) {
            energyState = MachineStatus.EnergyState.INSERTING;
            fluidState = FluidState.FILLING;
            addEnergy();
            fillWater();
        } else {
            if((energyAmount() < 10_000_000 && hasEnergySource()) || (fluidAmount() < 810000 && hasWaterBucket())) {
                energyState = MachineStatus.EnergyState.INSERTING;
                addEnergy();
                fluidState = FluidState.FILLING;
                fillWater();
            } else {
                energyState = MachineStatus.EnergyState.IDLE;
                fluidState= FluidState.IDLE;
            }
        }

        validateEnergyAmount(ENERGY_SLOT);
        validateWaterAmount();
        validateRedstoneAmount(REDSTONE_SLOT);
        setChanged(level, pos, state);
    }

    private void modifyState() {
        BlockState state = getBlockState();
        state = state.setValue(GemPurifierBlock.IS_POLISHING, gemstone());
        if(state != getBlockState()) {
            level.setBlock(worldPosition, state, Block.UPDATE_ALL);
        }
    }

    @Override
    protected void addEnergy() {
        if(!hasEnergySource() || energyAmount() >= 10_000_000) {
            energyState = MachineStatus.EnergyState.IDLE;
            return;
        }
        int amount = energyStack().is(ModItems.ENERGY_INGOT.get()) ? 1024 : 1536;
        if(level.hasNeighborSignal(worldPosition)) amount *= 5;
        try(Transaction transaction = Transaction.openRoot()) {
            long inserted = energyHandler().insert(amount, transaction);
            transaction.commit();
            if(inserted > 0) energyState = MachineStatus.EnergyState.INSERTING;
            else energyState = MachineStatus.EnergyState.IDLE;
        }
    }

    @Override
    protected void useEnergy() {
        int amount = level.hasNeighborSignal(worldPosition) ? 640 : 128;
        try(Transaction transaction = Transaction.openRoot()) {
            long extracted = energyHandler().extract(amount, transaction);
            energyExtracted += extracted;
            transaction.commit();
        }
        energyState = MachineStatus.EnergyState.EXTRACTING;
    }

    private Optional<RecipeHolder<GemPurifierRecipe>> getCurrentRecipe() {
        if(!(level instanceof ServerLevel serverLevel)) {
            return Optional.empty();
        }
        return matchGetter.getRecipeFor(new GemPurifyingRecipeInput(main.getResource(INGREDIENT_SLOT).toStack()), serverLevel);
    }

    private void getPurifiedGemstone() {
        Optional<RecipeHolder<GemPurifierRecipe>> recipe = getCurrentRecipe();
        if(recipe.isEmpty()) {
            return;
        }
        ItemStack output = recipe.get().value().result();

        try(Transaction transaction = Transaction.openRoot()) {
            ItemAccess itemAccess = ItemAccess.forHandlerIndex(main, RESULT_SLOT);

            ContainerHelper.removeItem(main.copyToList(), INGREDIENT_SLOT,1);
            main.set(RESULT_SLOT, ItemResource.of(output), itemAccess.getAmount() + output.getCount());

            transaction.commit();
        }
    }

//    private void getPurifiedGemstone() {
//        RecipeHolder<GemPurifierRecipe> recipe = getCurrentRecipe().orElseThrow();
//
//        ItemAccess itemAccess = ItemAccess.forHandlerIndex(main, RESULT_SLOT);
//
//        ContainerHelper.removeItem(main.copyToList(), INGREDIENT_SLOT, 1);
//
//        main.set(RESULT_SLOT, ItemResource.of(recipe.value().result()),
//                itemAccess.getAmount() + recipe.value().result().getCount());
//    }

    private boolean hasPurificationFinished() {
        return initialProgressTicks >= maxProgress;
    }

    @Override
    public void validateEnergyAmount(int slot) {
        if(energyAmount() > 10000000) {
            energyHandler().set(10000000);
        }

        long energy = this.energyAmount();

        long [] milestones = {1000000, 2000000, 3000000, 4000000, 5000000, 6000000, 7000000, 8000000, 9000000, 10000000};

        for(long milestone : milestones) {
            if(energy == milestone && previousRemovedEnergyMilestone < milestone) {
                ContainerHelper.removeItem(main.copyToList(), ENERGY_SLOT, 1);
                previousRemovedEnergyMilestone = milestone;
                break;
            }
        }
    }

    private void validateWaterAmount() {
        int water = fluidAmount();

        long [] milestones = {1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000};

        for(long milestone : milestones) {
            if(water >= milestone && previousFluidMilestone < milestone) {
                ContainerHelper.removeItem(main.copyToList(), WATER_SLOT, 1);
                main.set(WATER_SLOT, ItemResource.of(new ItemStack(Items.BUCKET)), 1);
                previousFluidMilestone = milestone;
                break;
            }
        }
    }
    
    private boolean hasEnoughWater() {
        return fluid().getAmount() >= 15;
    }

    @Override
    protected boolean hasRequiredEnergyAmount() {
        return this.energyHandler.getAmountAsInt() >= 128;
    }

    private boolean canResultAccept() {
        return this.resultStack().isEmpty() || this.resultStack().toStack().getCount() < this.resultStack().getMaxStackSize();
    }

    @Override
    public void increaseProgressTicks() {
        if(level == null) {
            return;
        }
        if(this.level.hasNeighborSignal(getBlockPos())) {
            initialProgressTicks += 5;
        } else {
            initialProgressTicks++;
        }
    }

    @Override
    public GemCategory category() {
        return GemCategory.PURIFYING;
    }

    private void fillWater() {
        if(!hasWaterBucket() || fluidAmount() >= 10000) {
            fluidState = FluidState.IDLE;
            return;
        }
        int amount = 20;
        try(Transaction transaction = Transaction.openRoot()) {
            int inserted = fluidHandler.insert(FluidResource.of(Fluids.WATER), amount, transaction);
            transaction.commit();
            if(inserted > 0) fluidState = FluidState.FILLING;
            else fluidState = FluidState.IDLE;
        }
    }

    private void consumeWater() {
        int amount = 10;
        try(Transaction transaction = Transaction.openRoot()) {
            fluidHandler.extract(FluidResource.of(Fluids.WATER), amount, transaction);
            transaction.commit();
        }
        fluidState = FluidState.EMPTYING;
    }

    private boolean canInsertItemIntoResultSlot(Item item) {
        return this.resultStack().getItem() == item || this.resultStack().isEmpty() || this.resultStack().is(ModItemTags.GEMSTONE)
                || this.resultStack().is(ModItemTags.RAW_GEMSTONE);
    }

    private boolean canInsertCountIntoResultSlot(ItemStack result) {
        return this.resultStack().toStack().getCount() + result.getCount() <= this.resultStack().getMaxStackSize();
    }

    @Override
    public ItemResource redstoneStack(int slot) {
        return getStack(REDSTONE_SLOT);
    }

    private boolean hasWaterBucket() {
        return !fluidStack().isEmpty() && fluidStack().is(Items.WATER_BUCKET);
    }

    public enum FluidState implements StringRepresentable {
        IDLE("idle"),
        FILLING("filling"),
        EMPTYING("emptying");

        private final String name;

        FluidState(String name) {
            this.name = name;
        }

        public static final Codec<FluidState> CODEC = StringRepresentable.fromValues(FluidState::values);

        @Override
        public String getSerializedName() {
            return this.name;
        }
    }
}