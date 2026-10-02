package net.fireboy.milestone.menu;

import net.fireboy.milestone.milestone.MilestoneGameplayManager;
import net.fireboy.milestone.milestone.MilestoneRecord;
import net.fireboy.milestone.milestone.MilestoneState;
import net.fireboy.milestone.milestone.MilestoneType;
import net.fireboy.milestone.registry.ModBlocks;
import net.fireboy.milestone.registry.ModMenus;
import net.fireboy.milestone.task.ItemSubmissionTaskDefinition;
import net.fireboy.milestone.task.MilestoneTaskCatalog;
import net.fireboy.milestone.task.MilestoneTaskDefinition;
import net.fireboy.milestone.task.MilestoneTaskType;
import net.fireboy.milestone.task.TaskAssignment;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Milestone Core menu. Item objectives expose a real submission slot alongside
 * the player's inventory; hunt and advancement objectives keep that slot
 * inactive and continue to complete from world events.
 */
public final class MilestoneMenu extends AbstractContainerMenu {
    /** Retained for compatibility with v0.15 clients/debug paths. */
    public static final int BUTTON_SUBMIT_MAIN_HAND = 0;

    public static final int SUBMISSION_SLOT_X = 150;
    public static final int SUBMISSION_SLOT_Y = 47;
    public static final int PLAYER_INVENTORY_X = 8;
    public static final int PLAYER_INVENTORY_Y = 155;
    public static final int HOTBAR_Y = 213;

    private static final int SUBMISSION_MENU_SLOT = 0;
    private static final int PLAYER_MAIN_START = 1;
    private static final int PLAYER_MAIN_END = 28;
    private static final int PLAYER_HOTBAR_START = 28;
    private static final int PLAYER_HOTBAR_END = 37;

    private static final int DATA_STATE = 0;
    private static final int DATA_TYPE = 1;
    private static final int DATA_CONTRIBUTORS = 2;
    private static final int DATA_MINIMUM = 3;
    private static final int DATA_TASK_INDEX = 4;
    private static final int DATA_TASK_COMPLETE = 5;
    private static final int DATA_COUNT = 6;

    private final BlockPos corePos;
    private final ContainerLevelAccess access;
    private final ContainerData data;
    private final SimpleContainer submissionContainer;
    private final Player menuPlayer;
    private boolean processingSubmission;

    /** Client-side constructor used by IContainerFactory. */
    public MilestoneMenu(int containerId, Inventory playerInventory, RegistryFriendlyByteBuf buffer) {
        this(
                containerId,
                playerInventory,
                buffer.readBlockPos(),
                ContainerLevelAccess.NULL,
                new SimpleContainerData(DATA_COUNT),
                new SimpleContainer(1)
        );
    }

    /** Server-side constructor. */
    public MilestoneMenu(int containerId, Inventory playerInventory, ServerLevel level, BlockPos corePos) {
        this(
                containerId,
                playerInventory,
                corePos,
                ContainerLevelAccess.create(level, corePos),
                createServerData(level, corePos, playerInventory.player),
                new SimpleContainer(1)
        );
    }

    private MilestoneMenu(
            int containerId,
            Inventory playerInventory,
            BlockPos corePos,
            ContainerLevelAccess access,
            ContainerData data,
            SimpleContainer submissionContainer
    ) {
        super(ModMenus.MILESTONE.get(), containerId);
        this.corePos = corePos.immutable();
        this.access = access;
        this.data = data;
        this.submissionContainer = submissionContainer;
        this.menuPlayer = playerInventory.player;

        checkContainerDataCount(data, DATA_COUNT);
        checkContainerSize(submissionContainer, 1);
        addDataSlots(data);
        submissionContainer.addListener(this::slotsChanged);

        addSlot(new Slot(submissionContainer, 0, SUBMISSION_SLOT_X, SUBMISSION_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                ItemSubmissionTaskDefinition task = itemSubmissionTask();
                return task != null
                        && !taskComplete()
                        && stack.is(task.item());
            }

            @Override
            public int getMaxStackSize(ItemStack stack) {
                ItemSubmissionTaskDefinition task = itemSubmissionTask();
                if (task == null || taskComplete() || !stack.is(task.item())) {
                    return 0;
                }
                return Math.min(task.count(), stack.getMaxStackSize());
            }

            @Override
            public boolean isActive() {
                return itemSubmissionTask() != null && !taskComplete();
            }
        });

        // Main player inventory: inventory indices 9..35.
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(
                        playerInventory,
                        column + row * 9 + 9,
                        PLAYER_INVENTORY_X + column * 18,
                        PLAYER_INVENTORY_Y + row * 18
                ));
            }
        }

        // Hotbar: inventory indices 0..8.
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(
                    playerInventory,
                    column,
                    PLAYER_INVENTORY_X + column * 18,
                    HOTBAR_Y
            ));
        }
    }

    private static ContainerData createServerData(ServerLevel level, BlockPos corePos, Player player) {
        return new ContainerData() {
            @Override
            public int get(int index) {
                MilestoneRecord record = MilestoneGameplayManager.recordAt(level, corePos);
                if (record == null) {
                    return index == DATA_TASK_INDEX ? -1 : 0;
                }

                TaskAssignment assignment = record.assignment(player.getUUID()).orElse(null);
                return switch (index) {
                    case DATA_STATE -> record.state().ordinal();
                    case DATA_TYPE -> record.type().ordinal();
                    case DATA_CONTRIBUTORS -> record.contributorCount();
                    case DATA_MINIMUM -> record.type().minimumContributors();
                    case DATA_TASK_INDEX -> assignment == null
                            ? -1
                            : MilestoneTaskCatalog.indexOf(record.type(), assignment.taskId());
                    case DATA_TASK_COMPLETE -> assignment != null && assignment.completed() ? 1 : 0;
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                // Read-only from the menu's perspective.
            }

            @Override
            public int getCount() {
                return DATA_COUNT;
            }
        };
    }

    public BlockPos corePos() {
        return corePos;
    }

    public MilestoneState milestoneState() {
        int ordinal = data.get(DATA_STATE);
        MilestoneState[] values = MilestoneState.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : MilestoneState.DORMANT;
    }

    public MilestoneType milestoneType() {
        int ordinal = data.get(DATA_TYPE);
        MilestoneType[] values = MilestoneType.values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : MilestoneType.GENERIC;
    }

    public int contributorCount() {
        return data.get(DATA_CONTRIBUTORS);
    }

    public int minimumContributors() {
        return data.get(DATA_MINIMUM);
    }

    public boolean taskComplete() {
        return data.get(DATA_TASK_COMPLETE) != 0;
    }

    public MilestoneTaskDefinition taskDefinition() {
        return MilestoneTaskCatalog.byIndex(milestoneType(), data.get(DATA_TASK_INDEX));
    }

    public MilestoneTaskType taskType() {
        MilestoneTaskDefinition definition = taskDefinition();
        return definition == null ? null : definition.type();
    }

    public MilestoneType.RewardThreshold nextThreshold() {
        return milestoneType().nextThresholdAfter(contributorCount());
    }

    private ItemSubmissionTaskDefinition itemSubmissionTask() {
        MilestoneTaskDefinition definition = taskDefinition();
        return definition instanceof ItemSubmissionTaskDefinition itemTask ? itemTask : null;
    }

    /**
     * When the requested amount is present in the Core slot, consume it and
     * complete the objective immediately. Partial stacks can sit in the slot
     * until the player adds the rest.
     */
    @Override
    public void slotsChanged(net.minecraft.world.Container container) {
        super.slotsChanged(container);
        if (container == submissionContainer) {
            trySubmitFromSlot();
        }
    }

    private void trySubmitFromSlot() {
        if (processingSubmission || !(menuPlayer instanceof ServerPlayer serverPlayer)) {
            return;
        }

        ItemSubmissionTaskDefinition task = itemSubmissionTask();
        if (task == null || taskComplete()) {
            return;
        }

        ItemStack stack = submissionContainer.getItem(0);
        if (stack.isEmpty() || !stack.is(task.item()) || stack.getCount() < task.count()) {
            return;
        }

        processingSubmission = true;
        try {
            MilestoneGameplayManager.useItem(
                    serverPlayer.serverLevel(),
                    corePos,
                    serverPlayer,
                    stack
            );

            // Creative players do not have their submitted stack shrunk. Never
            // leave that stack trapped in a slot which has just become inactive.
            if (taskComplete() && !submissionContainer.getItem(0).isEmpty()) {
                ItemStack remainder = submissionContainer.removeItemNoUpdate(0);
                serverPlayer.getInventory().placeItemBackInInventory(remainder);
            }
            submissionContainer.setChanged();
            broadcastChanges();
        } finally {
            processingSubmission = false;
        }
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (id != BUTTON_SUBMIT_MAIN_HAND || !(player instanceof ServerPlayer serverPlayer)) {
            return false;
        }

        // Compatibility fallback for the old v0.15 main-hand submit button.
        if (!(taskDefinition() instanceof ItemSubmissionTaskDefinition)) {
            return false;
        }

        MilestoneGameplayManager.useItem(
                serverPlayer.serverLevel(),
                corePos,
                serverPlayer,
                serverPlayer.getMainHandItem()
        );
        broadcastChanges();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) {
            return ItemStack.EMPTY;
        }

        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();

        if (index == SUBMISSION_MENU_SLOT) {
            if (!moveItemStackTo(stack, PLAYER_MAIN_START, PLAYER_HOTBAR_END, true)) {
                return ItemStack.EMPTY;
            }
        } else if (slots.get(SUBMISSION_MENU_SLOT).mayPlace(stack)) {
            // Shift-clicking the requested item feeds the Core slot first.
            if (!moveItemStackTo(stack, SUBMISSION_MENU_SLOT, SUBMISSION_MENU_SLOT + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else if (index >= PLAYER_MAIN_START && index < PLAYER_MAIN_END) {
            if (!moveItemStackTo(stack, PLAYER_HOTBAR_START, PLAYER_HOTBAR_END, false)) {
                return ItemStack.EMPTY;
            }
        } else if (index >= PLAYER_HOTBAR_START && index < PLAYER_HOTBAR_END) {
            if (!moveItemStackTo(stack, PLAYER_MAIN_START, PLAYER_MAIN_END, false)) {
                return ItemStack.EMPTY;
            }
        } else {
            return ItemStack.EMPTY;
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        return original;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        clearContainer(player, submissionContainer);
    }

    @Override
    public boolean stillValid(Player player) {
        return AbstractContainerMenu.stillValid(access, player, ModBlocks.MILESTONE_CORE.get());
    }
}
