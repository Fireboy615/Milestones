package net.fireboy.milestone.registry;

import net.fireboy.milestone.Milestone;
import net.fireboy.milestone.blockentity.MilestoneCoreBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Milestone.MOD_ID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MilestoneCoreBlockEntity>> MILESTONE_CORE =
            BLOCK_ENTITIES.register(
                    "milestone_core",
                    () -> BlockEntityType.Builder.of(
                            MilestoneCoreBlockEntity::new,
                            ModBlocks.MILESTONE_CORE.get()
                    ).build(null)
            );

    private ModBlockEntities() {
    }
}
