package com.minesight.client.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Works around a native crash (EXCEPTION_ACCESS_VIOLATION in glfw.dll) that fires
 * from {@code RenderSystem.limitDisplayFPS} -> {@code glfwWaitEventsTimeout} when
 * the game window is <b>unfocused</b> - which is the normal state for the
 * background farm-camera windows (a focused single client doesn't hit it).
 *
 * <p>Replace that GLFW event-wait with a plain sleep: the frame-rate throttle is
 * preserved (so background cameras don't peg the GPU), OS events still pump via
 * the main loop's poll each frame, and the crashing native call is never made.
 *
 * <p>{@code require = 0} so that if the target ever shifts across versions, the
 * client still launches (it just loses this workaround) rather than failing to
 * load.
 */
@Mixin(RenderSystem.class)
public class RenderSystemMixin {

    @Redirect(
            method = "limitDisplayFPS",
            at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwWaitEventsTimeout(D)V"),
            require = 0
    )
    private static void minesight$sleepInsteadOfGlfwWait(double timeout) {
        if (timeout > 0.0) {
            try {
                Thread.sleep((long) (timeout * 1000.0));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
