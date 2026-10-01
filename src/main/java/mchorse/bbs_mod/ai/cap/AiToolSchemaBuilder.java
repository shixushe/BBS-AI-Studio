package mchorse.bbs_mod.ai.cap;

import java.util.List;

/**
 * Compresses the {@link CapabilityManifest} into what a language model may
 * see, with the two-level pruning from the copilot spec (section 10.2): by
 * default only the catalog INDEX ships in the prompt (id + description +
 * value count); a model that needs more asks for one form and gets that
 * form's full value table in a separate call. A full dump of every form's
 * values would blow the context on its own.
 */
public class AiToolSchemaBuilder
{
    /** Level 1: the catalog index (cheap, always included). */
    public static String index(CapabilityManifest manifest)
    {
        StringBuilder builder = new StringBuilder();

        builder.append("forms:\n").append(AiCapabilityScanner.index(manifest));

        builder.append("keyframes: ");

        for (CapabilityManifest.KeyframeCapability keyframe : manifest.keyframes)
        {
            builder.append(keyframe.type).append(keyframe.numeric ? "*" : " ");
        }

        builder.append("\ntracks: ").append(String.join(",", manifest.tracks));

        if (!manifest.importers.isEmpty())
        {
            builder.append("\nimporters: ").append(String.join(",", manifest.importers));
        }

        if (!manifest.skills.isEmpty())
        {
            builder.append("\naddon skills: ").append(String.join(",", manifest.skills));
        }

        builder.append("\n(* = numeric, curve polish applies)\n");

        return builder.toString();
    }

    /** Level 2: one form's full value table, on demand. */
    public static String expand(CapabilityManifest manifest, String formId)
    {
        CapabilityManifest.FormCapability form = manifest.form(formId);

        if (form == null)
        {
            return null;
        }

        StringBuilder builder = new StringBuilder();

        builder.append(form.id).append(" [").append(form.source).append("]\n");

        for (CapabilityManifest.ValueInfo value : form.values)
        {
            builder.append("  ").append(value.name)
                .append(" type=").append(value.type)
                .append(value.numeric ? " numeric" : "")
                .append("\n");
        }

        return builder.toString();
    }

    /** The declared addon skills, as a prompt fragment (label + schema). */
    public static String skills(List<mchorse.bbs_mod.api.AiSkill> skills)
    {
        StringBuilder builder = new StringBuilder();

        for (mchorse.bbs_mod.api.AiSkill skill : skills)
        {
            builder.append(skill.id).append(" - ").append(skill.description.get())
                .append("\nparams: ").append(skill.paramsSchema).append("\n");
        }

        return builder.toString();
    }
}
