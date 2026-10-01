package mchorse.bbs_mod.ai.creative;

import mchorse.bbs_mod.ai.AiException;
import mchorse.bbs_mod.ai.plan.AnimationPlan;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.data.types.BaseType;
import mchorse.bbs_mod.data.types.ListType;
import mchorse.bbs_mod.data.types.MapType;

import java.util.ArrayList;
import java.util.List;

/**
 * The creative mode's product (copilot spec section 11.2): MULTIPLE
 * directions for one fuzzy theme - never a single result. Proposals live on
 * the creative board until the user explicitly adopts ONE variant; the film
 * is untouched before that (spec 11.2 rule 2).
 */
public class CreativeProposal
{
    public String theme = "";

    public final List<Variant> variants = new ArrayList<>();

    public static class Variant
    {
        public AnimationPlan plan;
        public String notes = "";
    }

    /**
     * Parse the model's multi-variant reply: {"variants": [ {plan fields...,
     * "notes": "..."}, ... ]}. Each variant re-enters the STRICT plan
     * validator - creative mode relaxes nothing about the contract (spec
     * 11.4: only the write path differs, never the data quality).
     */
    public static CreativeProposal parse(String theme, String json) throws AiException
    {
        MapType map = DataToString.mapFromString(json);

        if (map == null)
        {
            throw new AiException(AiException.Type.PARSE, "Creative reply is not valid JSON");
        }

        BaseType variants = map.get("variants");

        if (!BaseType.isList(variants) || variants.asList().isEmpty())
        {
            throw new AiException(AiException.Type.PARSE, "Creative reply has no variants");
        }

        CreativeProposal proposal = new CreativeProposal();

        proposal.theme = theme;

        ListType list = variants.asList();

        for (int i = 0; i < list.size(); i++)
        {
            if (!list.get(i).isMap())
            {
                continue;
            }

            MapType variantMap = list.get(i).asMap();
            Variant variant = new Variant();

            variant.notes = variantMap.getString("notes", "");
            variant.plan = AnimationPlan.parse(DataToString.toString(variantMap, true));

            proposal.variants.add(variant);
        }

        if (proposal.variants.isEmpty())
        {
            throw new AiException(AiException.Type.PARSE, "Every variant failed validation");
        }

        return proposal;
    }

    /** Serialize for the creative board's draft persistence (spec 5.5 hard rule 1). */
    public MapType toData()
    {
        MapType map = new MapType();

        map.putString("theme", this.theme);

        ListType variants = new ListType();

        for (Variant variant : this.variants)
        {
            if (variant.plan == null)
            {
                continue;
            }

            MapType data = variant.plan.toData();

            data.putString("notes", variant.notes);
            variants.add(data);
        }

        map.put("variants", variants);

        return map;
    }

    @SuppressWarnings("unused")
    private static CreativeProposal fromData(MapType map) throws AiException
    {
        return CreativeProposal.parse(map.getString("theme", ""), DataToString.toString(map, true));
    }
}
