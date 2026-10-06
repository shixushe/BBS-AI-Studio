package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.cubic.IBoneHierarchy;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.forms.forms.BodyPart;
import mchorse.bbs_mod.forms.forms.MobForm;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.MobFormRenderer;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.utils.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks a replay's form tree - the root form plus every nested body part - to
 * answer two questions the AI animation pipeline has about "which end" to solve
 * at: which form paths actually own a given bone (the root end, a body part
 * end, or both), and the union inventory of rig bones available for binding.
 *
 * <p>Models like Star 3.6 nest the real rig under a body part while the replay's
 * root form is a bare holder; writing pose keys only to the root end would then
 * animate nothing. Bone channels are addressed per form path
 * ({@code pose.bones.X} at the root, {@code <part>/pose.bones.X} on a part), so
 * the solver writes to every end that owns the bone.</p>
 */
public class AiFormWalker
{
    private static final int MAX_DEPTH = 8;

    /**
     * Bone name -> form paths owning a rig with that bone, root path "" first
     * when the root itself has it. Paths are TrackId form paths ("", "0", "0/1"...).
     */
    public static Map<String, List<String>> collectBoneEnds(Form root)
    {
        Map<String, Set<String>> ends = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();

        walk(root, "", ends, order, new HashSet<>(), 0);

        Map<String, List<String>> out = new HashMap<>();

        for (String bone : order)
        {
            out.put(bone, new ArrayList<>(ends.get(bone)));
        }

        return out;
    }

    /** Every rig bone in the tree, root's own bones first, then parts' - for binding. */
    public static List<String> collectBones(Form root)
    {
        return new ArrayList<>(collectBoneEnds(root).keySet());
    }

    /**
     * Bone name -> parent bone name (per the model's own rig hierarchy, first
     * model wins for duplicated names). Feeds the topology-aware bone binding
     * inference and the skeleton-hierarchy prompt conditioning.
     */
    public static Map<String, String> collectHierarchy(Form root)
    {
        Map<String, String> parentOf = new LinkedHashMap<>();

        try
        {
            collectHierarchyInto(root, parentOf, new HashSet<>(), 0);
        }
        catch (Exception ignored)
        {}

        return parentOf;
    }

    private static void collectHierarchyInto(Form form, Map<String, String> parentOf, Set<Form> visited, int depth)
    {
        if (form == null || depth > MAX_DEPTH || !visited.add(form))
        {
            return;
        }

        if (form instanceof ModelForm modelForm)
        {
            ModelInstance instance = ModelFormRenderer.getModel(modelForm);

            if (instance != null && instance.model != null)
            {
                for (String bone : instance.model.getGroupKeysInHierarchyOrder())
                {
                    if (bone == null || bone.isEmpty() || parentOf.containsKey(bone))
                    {
                        continue;
                    }

                    try
                    {
                        String parent = instance.model.getParentGroupKey(bone);

                        parentOf.put(bone, parent == null ? "" : parent);
                    }
                    catch (Exception ignored)
                    {
                        parentOf.put(bone, "");
                    }
                }
            }
        }

        for (mchorse.bbs_mod.forms.forms.BodyPart part : form.parts.getAllTyped())
        {
            collectHierarchyInto(part.getForm(), parentOf, visited, depth + 1);
        }
    }

    private static void walk(Form form, String path, Map<String, Set<String>> ends, List<String> order, Set<Form> visited, int depth)
    {
        if (form == null || depth > MAX_DEPTH || !visited.add(form))
        {
            return;
        }

        List<String> bones = bonesOf(form);

        for (String bone : bones)
        {
            /* 部分骨架（BOBJ 等）的骨骼表可能带 null 键，跳过 */
            if (bone == null || bone.isEmpty())
            {
                continue;
            }

            Set<String> paths = ends.computeIfAbsent(bone, (k) -> new LinkedHashSet<>());

            if (paths.add(path))
            {
                order.add(bone);
            }
        }

        for (BodyPart part : form.parts.getAllTyped())
        {
            walk(part.getForm(), StringUtils.combinePaths(path, part.getId()), ends, order, visited, depth + 1);
        }
    }

    private static List<String> bonesOf(Form form)
    {
        try
        {
            if (form instanceof ModelForm modelForm)
            {
                ModelInstance instance = ModelFormRenderer.getModel(modelForm);

                if (instance != null && instance.model != null)
                {
                    return instance.model.getGroupKeysInHierarchyOrder();
                }
            }
            else if (form instanceof MobForm mobForm)
            {
                IBoneHierarchy rig = MobFormRenderer.getRig(mobForm);

                if (rig != null)
                {
                    return rig.getGroupKeysInHierarchyOrder();
                }
            }
        }
        catch (Exception e)
        {
            /* An unreachable model (no renderer context yet) just means no
             * bones at this end - the walk continues below with body parts */
        }

        return List.of();
    }
}
