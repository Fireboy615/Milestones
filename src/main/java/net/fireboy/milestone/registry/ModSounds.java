package net.fireboy.milestone.registry;

import net.fireboy.milestone.Milestone;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/** The original jumpscare audio, extracted from the user's provided MP4. */
public final class ModSounds {
    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, Milestone.MOD_ID);

    public static final Supplier<SoundEvent> FOXY_JUMPSCARE =
            SOUNDS.register("foxy_jumpscare", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(Milestone.MOD_ID, "foxy_jumpscare")));

    private ModSounds() { }
}
