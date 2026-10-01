package mchorse.bbs_mod.api.events;

import mchorse.bbs_mod.api.AiSkill;

/**
 * Posted once BBS's own forms and keyframe factories are registered - the
 * moment an addon's AI skills can be declared. Addons subscribe and call
 * {@link #register(AiSkill)}.
 *
 * <p>This event extends the addon API surface: it ships with a
 * {@code BBSApi.VERSION} bump and an updated {@code api/bbs-api.txt}
 * snapshot (build constraint, copilot spec section 1).</p>
 */
public class RegisterAiSkillsEvent
{
    /**
     * Declare a skill. Un-namespaced ids are rejected here with a readable
     * log line - the addon's other skills still register.
     */
    public void register(AiSkill skill)
    {
        mchorse.bbs_mod.ai.cap.AiSkills.register(skill);
    }
}
