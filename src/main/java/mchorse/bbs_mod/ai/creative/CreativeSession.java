package mchorse.bbs_mod.ai.creative;

import mchorse.bbs_mod.data.types.MapType;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * One creative mode session: every batch KEEPS its proposals (换一批 never
 * discards - the user must be able to come back and compare, spec 11.3), a
 * conservative per-session call cap stops the key from burning silently, and
 * drafts persist so closing the panel loses nothing (spec 5.5 hard rule 1).
 */
public class CreativeSession
{
    /** Candidates per batch: spec 11.2 - default conservative, hard limit 5. */
    public static final int DEFAULT_CANDIDATES = 3;
    public static final int MAX_CANDIDATES = 5;

    /** Calls per session before the UI must stop and ask (spec 11.3). */
    public static final int MAX_CALLS = 6;

    public final List<CreativeProposal> proposals = new ArrayList<>();

    public int calls;

    public boolean canCall()
    {
        return this.calls < MAX_CALLS;
    }

    public void add(CreativeProposal proposal)
    {
        this.calls++;
        this.proposals.add(proposal);
    }

    /** Flat variant list over every kept proposal (selection indices address this). */
    public List<CreativeProposal.Variant> variants()
    {
        List<CreativeProposal.Variant> variants = new ArrayList<>();

        for (CreativeProposal proposal : this.proposals)
        {
            variants.addAll(proposal.variants);
        }

        return variants;
    }

    /** Draft persistence: one JSON per session under <settings>/ai_creative. */
    public static File draftFile(File settingsFolder, String themeSlug)
    {
        return new File(new File(settingsFolder, "ai_creative"), themeSlug + ".json");
    }

    public void save(File file)
    {
        MapType map = new MapType();

        map.putInt("calls", this.calls);

        mchorse.bbs_mod.data.types.ListType list = new mchorse.bbs_mod.data.types.ListType();

        for (CreativeProposal proposal : this.proposals)
        {
            list.add(proposal.toData());
        }

        map.put("proposals", list);
        mchorse.bbs_mod.data.DataToString.writeSilently(file, map, true);
    }
}
