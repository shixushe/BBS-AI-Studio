package bbsplus.example.bbsplus.mixin.client;

import bbsplus.example.bbsplus.client.BbsplusCurveEditorState;
import mchorse.bbs_mod.api.client.editor.TrackCategory;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor.OrbitReaction;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframes;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps the keyframe curve editor open — and keeps its viewport stable — across
 * editor rebuilds triggered by undo/redo.
 */
@Mixin(UIReplaysEditor.class)
public abstract class MixinUIReplaysEditor
{
    @Shadow private TrackCategory category;
    @Shadow private UIFilmPanel filmPanel;
    @Shadow private Film film;
    @Shadow private boolean settingReplay;

    @Unique private BbsplusCurveEditorState bbsplus$editingState;
    @Unique private boolean bbsplus$changingCategory;
    @Unique private boolean bbsplus$changingFilm;
    @Unique private String bbsplus$lastReplayId;
    @Unique private TrackCategory bbsplus$targetCategory;
    @Unique private final Map<String, Map<TrackCategory, BbsplusCurveEditorState>> bbsplus$curveStates = new HashMap<>();

    @Inject(method = "setFilm", at = @At("HEAD"))
    private void bbsplus$beginFilmSwitch(Film film, CallbackInfo ci)
    {
        this.bbsplus$changingFilm = true;
    }

    @Inject(method = "setFilm", at = @At("RETURN"))
    private void bbsplus$finishFilmSwitch(Film film, CallbackInfo ci)
    {
        this.bbsplus$changingFilm = false;
    }

    @Inject(method = "setCategory", at = @At("HEAD"))
    private void bbsplus$savePoseTabsBeforeCategorySwitch(TrackCategory category, CallbackInfo ci)
    {
        UIReplaysEditor self = (UIReplaysEditor) (Object) this;

        this.bbsplus$saveCurrentCategoryCurveState(self);
        this.bbsplus$changingCategory = true;
        this.bbsplus$targetCategory = category;
    }

    @Inject(method = "setCategory", at = @At("TAIL"))
    private void bbsplus$finishCategorySwitch(TrackCategory category, CallbackInfo ci)
    {
        this.bbsplus$changingCategory = false;
        this.bbsplus$targetCategory = null;
    }

    @Inject(method = "updateChannelsList", at = @At("HEAD"))
    private void bbsplus$rememberEditingSheet(CallbackInfo ci)
    {
        this.bbsplus$editingState = null;

        UIReplaysEditor self = (UIReplaysEditor) (Object) this;
        UIKeyframeEditor editor = self.keyframeEditor;
        String replayId = this.bbsplus$getReplayId(self);
        boolean changingReplay = this.bbsplus$lastReplayId != null
            && !Objects.equals(this.bbsplus$lastReplayId, replayId);

        if (!this.bbsplus$changingCategory && !changingReplay)
        {
            this.bbsplus$editingState = this.bbsplus$captureCurveState(editor);
        }
    }

    @Inject(method = "updateChannelsList", at = @At("TAIL"))
    private void bbsplus$restoreEditingSheet(CallbackInfo ci)
    {
        UIReplaysEditor self = (UIReplaysEditor) (Object) this;
        BbsplusCurveEditorState state = this.bbsplus$changingCategory
            ? this.bbsplus$getSavedCurveState(self, this.bbsplus$targetCategory == null ? this.category : this.bbsplus$targetCategory)
            : this.bbsplus$editingState;

        if (state == null)
        {
            return;
        }

        this.bbsplus$restoreCurveState(self, state);
        this.bbsplus$editingState = null;
        this.bbsplus$lastReplayId = this.bbsplus$getReplayId(self);
    }

    @Inject(method = "updateChannelsList", at = @At("RETURN"))
    private void bbsplus$rememberVisibleReplay(CallbackInfo ci)
    {
        UIReplaysEditor self = (UIReplaysEditor) (Object) this;

        this.bbsplus$lastReplayId = this.bbsplus$getReplayId(self);
    }

    @Unique
    private BbsplusCurveEditorState bbsplus$captureCurveState(UIKeyframeEditor editor)
    {
        if (editor == null || editor.view == null || !editor.view.isEditing())
        {
            return null;
        }

        UIKeyframes view = editor.view;
        IUIKeyframeGraph graph = view.getGraph();
        UIKeyframeSheet sheet = graph.getLastSheet();

        if (sheet == null)
        {
            return null;
        }

        MapType graphState = new MapType();
        graph.saveState(graphState);

        return new BbsplusCurveEditorState(
            sheet.id,
            view.getXAxis().getMinValue(),
            view.getXAxis().getMaxValue(),
            graphState
        );
    }

    @Unique
    private void bbsplus$saveCurrentCategoryCurveState(UIReplaysEditor self)
    {
        if (self.getReplay() == null)
        {
            return;
        }

        String replayId = self.getReplay().getId();
        Map<TrackCategory, BbsplusCurveEditorState> states = this.bbsplus$curveStates.computeIfAbsent(
            replayId,
            id -> new HashMap<>()
        );
        BbsplusCurveEditorState state = this.bbsplus$captureCurveState(self.keyframeEditor);

        if (state == null)
        {
            states.remove(this.category);
        }
        else
        {
            states.put(this.category, state);
        }
    }

    @Unique
    private BbsplusCurveEditorState bbsplus$getSavedCurveState(UIReplaysEditor self, TrackCategory category)
    {
        if (self.getReplay() == null || category == null)
        {
            return null;
        }

        Map<TrackCategory, BbsplusCurveEditorState> states = this.bbsplus$curveStates.get(self.getReplay().getId());

        return states == null ? null : states.get(category);
    }

    @Unique
    private boolean bbsplus$restoreCurveState(UIReplaysEditor self, BbsplusCurveEditorState state)
    {
        UIKeyframeEditor editor = self.keyframeEditor;

        if (editor == null || editor.view == null)
        {
            return false;
        }

        UIKeyframes view = editor.view;
        UIKeyframeSheet sheet = view.getDopeSheet().getSheet(state.sheetId);

        if (sheet == null)
        {
            return false;
        }

        view.editSheet(sheet);
        view.getXAxis().view(state.xMin, state.xMax);
        view.getGraph().restoreState(state.graphState);

        return true;
    }

    @Unique
    private String bbsplus$getReplayId(UIReplaysEditor self)
    {
        return self.getReplay() == null ? null : self.getReplay().getId();
    }
}
