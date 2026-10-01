package mchorse.bbs_mod.ai.cap;

import mchorse.bbs_mod.BBSMod;
import mchorse.bbs_mod.api.AiSkill;
import mchorse.bbs_mod.forms.FormArchitect;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.base.BaseValue;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.utils.factory.MapFactory;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import mchorse.bbs_mod.resources.Link;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks the SAME registries the UI reads and produces the
 * {@link CapabilityManifest} - the model's tool list is generated, never
 * hand written, so anything a user can click becomes AI-callable without
 * touching AI code (copilot spec section 10.9; the reverse probe in the
 * acceptance list is the drift detector).
 *
 * <p>Caching: the scan runs once after registries settle and is re-scanned
 * only when the form type set changes - never per frame (section 10.2).</p>
 */
public class AiCapabilityScanner
{
    private static CapabilityManifest manifest;
    private static int lastFormHash = Integer.MIN_VALUE;
    private static List<String> importers = new ArrayList<>();

    /** Client fills importer names after RegisterImportersEvent. */
    public static void setImporters(List<String> names)
    {
        importers = names == null ? new ArrayList<>() : names;
        manifest = null;
    }

    /** The cached manifest, rescanned only when the form type set changed. */
    public static CapabilityManifest get()
    {
        int hash = FormArchitect.class.hashCode() * 31 + BBSMod.getForms().getKeys().hashCode();

        if (manifest == null || hash != lastFormHash)
        {
            manifest = scan();
            lastFormHash = hash;
        }

        return manifest;
    }

    public static CapabilityManifest scan()
    {
        CapabilityManifest result = new CapabilityManifest();

        scanForms(result);
        scanKeyframes(result);
        scanTracks(result);

        result.importers.addAll(importers);

        for (AiSkill skill : AiSkills.getSkills())
        {
            result.skills.add(skill.id);
        }

        return result;
    }

    /** Every registered form type, with its animatable value table from a sample instance. */
    private static void scanForms(CapabilityManifest result)
    {
        FormArchitect architect = BBSMod.getForms();

        for (Link type : architect.getKeys())
        {
            String id = type.toString();
            CapabilityManifest.FormCapability capability = new CapabilityManifest.FormCapability(id, sourceOf(type));

            result.forms.put(id, capability);

            try
            {
                Form sample = architect.create(type);

                collectValues(sample, capability.values);
            }
            catch (Exception e)
            {
                /* A form that cannot be instantiated for sampling still counts
                 * as a capability - it just carries an empty value table */
            }
        }
    }

    private static void collectValues(ValueGroup group, List<CapabilityManifest.ValueInfo> out)
    {
        for (BaseValue value : group.getAll())
        {
            String name = value.getId();
            String type = value.getClass().getSimpleName();
            boolean numeric = value instanceof mchorse.bbs_mod.settings.values.base.BaseKeyframeFactoryValue
                && KeyframeFactories.isNumeric(factoryOf(value));

            out.add(new CapabilityManifest.ValueInfo(name, type, numeric, true));
        }
    }

    private static IKeyframeFactory factoryOf(BaseValue value)
    {
        if (value instanceof KeyframeChannel channel)
        {
            return channel.getFactory();
        }

        if (value instanceof Keyframe key)
        {
            return key.getFactory();
        }

        return null;
    }

    /** bbs namespace vs addon namespace (anything registered by an event lands with its own mod id). */
    private static String sourceOf(Link type)
    {
        return type.source.equals("bbs") ? "bbs" : "addon:" + type.source;
    }

    /** Every registered keyframe factory and whether curves apply to it. */
    private static void scanKeyframes(CapabilityManifest result)
    {
        for (java.util.Map.Entry<String, IKeyframeFactory> entry : KeyframeFactories.FACTORIES.entrySet())
        {
            result.keyframes.add(new CapabilityManifest.KeyframeCapability(entry.getKey(), KeyframeFactories.isNumeric(entry.getValue())));
        }
    }

    /** Track kinds (the fixed vocabulary films address tracks with). */
    private static void scanTracks(CapabilityManifest result)
    {
        for (mchorse.bbs_mod.film.replays.tracks.TrackKind kind : mchorse.bbs_mod.film.replays.tracks.TrackKind.values())
        {
            result.tracks.add(kind.key);
        }
    }

    /** Compact catalog index for prompts: id + value count (level 1 of the two-level pruning). */
    public static String index(CapabilityManifest manifest)
    {
        StringBuilder builder = new StringBuilder();

        for (CapabilityManifest.FormCapability form : manifest.forms.values())
        {
            builder.append(form.id).append(" (").append(form.values.size()).append(" values, ").append(form.source).append(")\n");
        }

        return builder.toString();
    }
}
