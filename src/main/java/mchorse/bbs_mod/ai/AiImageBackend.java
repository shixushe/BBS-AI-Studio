package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.utils.resources.Pixels;

/**
 * Image generation backend, deliberately SEPARATE from
 * {@link AiTextBackend}: auth shape, rate limits, error codes and billing
 * units differ (copilot spec section 3 L1) - merging the two into one
 * interface is the mistake this abstraction exists to prevent.
 *
 * <p>Used only by the AI skin module (M7). Reference-image mode passes the
 * user's art through {@code reference}; backend support for it is part of
 * capability negotiation, not an assumption.</p>
 */
public interface AiImageBackend
{
    /**
     * Generate one image. Returns already-decoded pixels at the backend's
     * output resolution - downscaling to skin size is the pixel pipeline's
     * job, deliberately kept local.
     */
    public Pixels generate(String prompt, Pixels reference, int width, int height) throws AiException;
}
