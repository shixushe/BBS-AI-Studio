package mchorse.bbs_mod.ai.route;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.ai.commit.FrameDiff;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.model_editor.UIModelEditorPanel;
import mchorse.bbs_mod.ui.dashboard.textures.UITextureManagerPanel;

/**
 * Interface following for AI operations (copilot spec section 5.9): after a
 * commit, the interface jumps ONCE to the panel that owns the first affected
 * target, and every other change highlights in place - never one jump per
 * change (a twelve-keyframe commit must not strobe the user across screens).
 *
 * <p>Spec note (section 13.1: reality wins): this tree has no
 * {@code ContentType} enum - the panel routing table below IS the router,
 * keyed off {@code TrackId} prefixes. It deliberately shares the
 * {@code setPanel} path the creative panel's adoption uses; there is no
 * second navigation mechanism.</p>
 *
 * <p>Rules implemented here: jump only when {@code ai_follow} is on (default
 * on, one toggle in the AI settings); ensure the target panel is built before
 * focusing (the dashboard's lazy {@code buildNextStep}); and report failure
 * back instead of pretending - the caller shows why no jump happened.</p>
 */
public class AiTargetRouter
{
    /** Which panel owns a track address, and what to do once there. */
    public enum Target
    {
        FILM_TIMELINE(UIFilmPanel.class),
        MODEL_BONES(UIModelEditorPanel.class),
        TEXTURES(UITextureManagerPanel.class);

        public final Class<? extends mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel> panel;

        Target(Class<? extends mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel> panel)
        {
            this.panel = panel;
        }

        static Target of(String trackId)
        {
            if (trackId.startsWith(TrackId.BONE_PREFIX) || trackId.startsWith(TrackId.CONSTRAINT_PREFIX))
            {
                return MODEL_BONES;
            }

            if (trackId.startsWith(TrackId.MATERIAL_TEXTURE_PREFIX) || trackId.startsWith(TrackId.MATERIAL_PROP_PREFIX))
            {
                return TEXTURES;
            }

            return FILM_TIMELINE;
        }
    }

    /**
     * Follow the commit's first affected target. Returns false (and jumps
     * nothing) when following is off, the dashboard is unknown, or the target
     * panel could not be focused - the caller must say so, not stay silent.
     */
    public static boolean follow(UIDashboard dashboard, FrameDiff diff)
    {
        if (dashboard == null || diff == null || diff.entries.isEmpty() || !BBSSettings.aiFollow.get())
        {
            return false;
        }

        /* One operation, one jump: the FIRST target picks the panel */
        Target target = Target.of(diff.entries.get(0).trackId);

        if (target == Target.FILM_TIMELINE)
        {
            /* The film editor is where the user already is for these */
            return dashboard.getPanel(target.panel) != null;
        }

        /* Lazy construction: a panel that hasn't been built yet must be
         * finished BEFORE it can take focus (spec 5.9 rule 4) */
        while (!dashboard.isFullyBuilt())
        {
            dashboard.buildNextStep();
        }

        var panel = dashboard.getPanel(target.panel);

        if (panel == null)
        {
            return false;
        }

        dashboard.setPanel(panel);

        return true;
    }
}
