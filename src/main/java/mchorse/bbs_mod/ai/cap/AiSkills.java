package mchorse.bbs_mod.ai.cap;

import com.mojang.logging.LogUtils;
import mchorse.bbs_mod.api.AiSkill;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of addon-declared AI skills. The only gate is namespacing: an
 * un-namespaced {@code AiSkill.id} is refused with a readable reason (copilot
 * spec section 10.4) - without the namespace, uninstalling the addon would
 * drop the skill's data as if BBS itself had removed it.
 */
public class AiSkills
{
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<String, AiSkill> SKILLS = new LinkedHashMap<>();

    public static void register(AiSkill skill)
    {
        if (skill == null)
        {
            return;
        }

        if (!skill.isNamespaced())
        {
            LOGGER.error("AI skill '{}' was rejected: the id must be namespaced (e.g. 'myaddon:cloth_sim') - un-namespaced keys are indistinguishable from BBS's own removed keys and get dropped on save", skill.id);

            return;
        }

        if (SKILLS.containsKey(skill.id))
        {
            LOGGER.error("AI skill '{}' was rejected: a skill with this id is already registered", skill.id);

            return;
        }

        SKILLS.put(skill.id, skill);
    }

    public static List<AiSkill> getSkills()
    {
        return Collections.unmodifiableList(new ArrayList<>(SKILLS.values()));
    }

    public static AiSkill get(String id)
    {
        return SKILLS.get(id);
    }

    public static int size()
    {
        return SKILLS.size();
    }
}
