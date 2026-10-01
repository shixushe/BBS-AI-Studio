package bbsplus.example.bbsplus.mixin.client;

import bbsplus.example.bbsplus.pose.PoseBonePath;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.graphics.window.Window;
import mchorse.bbs_mod.ui.film.ICursor;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditorUtils;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.graphs.IUIKeyframeGraph;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mixin(UIReplaysEditorUtils.class)
public abstract class MixinUIReplaysEditorUtils
{
    @Unique private static int bbsplus$viewportBonePickButton = -1;

    @Inject(method = "pickFormWithOffers", at = @At("HEAD"))
    private static void bbsplus$captureViewportBonePickButton(UIContext context, Pair<Form, String> pair, UIReplaysEditorUtils.FormPicker picker, CallbackInfoReturnable<Boolean> cir)
    {
        bbsplus$viewportBonePickButton = Window.isCtrlPressed() && (context.mouseButton == 0 || context.mouseButton == 1)
            ? context.mouseButton
            : -1;
    }

    @Inject(method = "pickFormWithOffers", at = @At("RETURN"))
    private static void bbsplus$clearViewportBonePickButton(UIContext context, Pair<Form, String> pair, UIReplaysEditorUtils.FormPicker picker, CallbackInfoReturnable<Boolean> cir)
    {
        bbsplus$viewportBonePickButton = -1;
    }

    @Inject(
        method = "pickForm(Lmchorse/bbs_mod/ui/framework/elements/input/keyframes/UIKeyframeEditor;Lmchorse/bbs_mod/ui/film/ICursor;Lmchorse/bbs_mod/forms/forms/Form;Ljava/lang/String;Z)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void bbsplus$ctrlPickFormAdditively(UIKeyframeEditor keyframeEditor, ICursor cursor, Form form, String bone, boolean insert, CallbackInfo ci)
    {
        if (bbsplus$viewportBonePickButton < 0 || !Window.isCtrlPressed() || form == null || keyframeEditor == null || bone == null || bone.isEmpty())
        {
            return;
        }

        if (bbsplus$pickFormAdditively(keyframeEditor, cursor, form, bone, insert))
        {
            ci.cancel();
        }
    }

    @Unique
    private static boolean bbsplus$pickFormAdditively(UIKeyframeEditor keyframeEditor, ICursor cursor, Form form, String bone, boolean insert)
    {
        IUIKeyframeGraph graph = keyframeEditor.view.getGraph();
        String formPath = FormUtils.getPath(form);
        String boneKey = PoseBonePath.toPoseBoneKey(formPath, bone);

        if (insert)
        {
            return bbsplus$insertAdditively(keyframeEditor, cursor, graph, formPath, boneKey, bone);
        }

        Keyframe selected = graph.getSelected();
        UIKeyframeSheet currentSheet = selected != null ? graph.getSheet(selected) : null;
        PoseBonePath currentPath = currentSheet != null && currentSheet.id != null ? PoseBonePath.parse(currentSheet.id) : null;

        if (currentPath != null && !formPath.equals(currentPath.formPath))
        {
            return true;
        }

        if (bbsplus$isPoseSheet(currentSheet, formPath))
        {
            bbsplus$selectClosestAdditively(keyframeEditor, cursor, graph, currentSheet, bone);

            return true;
        }

        UIKeyframeSheet sheet = bbsplus$resolveBoneSheet(graph, boneKey, formPath);

        if (sheet == null)
        {
            return false;
        }

        PoseBonePath path = sheet.id != null ? PoseBonePath.parse(sheet.id) : null;
        String boneForEditor = path != null ? path.bone : bone;

        bbsplus$selectClosestAdditively(keyframeEditor, cursor, graph, sheet, boneForEditor);

        return true;
    }

    @Unique
    private static boolean bbsplus$insertAdditively(UIKeyframeEditor keyframeEditor, ICursor cursor, IUIKeyframeGraph graph, String formPath, String boneKey, String bone)
    {
        UIKeyframeSheet sheet = bbsplus$resolveBoneSheet(graph, boneKey, formPath);

        if (sheet == null)
        {
            return false;
        }

        if (bbsplus$isPoseSheet(sheet, formPath))
        {
            Keyframe existing = bbsplus$getKeyframeAt(sheet, cursor.getCursor());

            if (existing != null)
            {
                bbsplus$toggleSelection(graph, sheet, existing);
            }
            else
            {
                bbsplus$addKeyframeAdditively(graph, sheet, cursor.getCursor());
            }

            bbsplus$selectEditorBone(keyframeEditor, bone, true);

            return true;
        }

        Keyframe selected = graph.getSelected();
        UIKeyframeSheet currentSheet = selected != null ? graph.getSheet(selected) : null;

        if (bbsplus$isPoseSheet(currentSheet, formPath))
        {
            return true;
        }

        bbsplus$addKeyframeAdditively(graph, sheet, cursor.getCursor());

        PoseBonePath path = sheet.id != null ? PoseBonePath.parse(sheet.id) : null;

        bbsplus$selectEditorBone(keyframeEditor, path != null ? path.bone : bone, true);

        return true;
    }

    @Unique
    private static void bbsplus$selectClosestAdditively(UIKeyframeEditor keyframeEditor, ICursor cursor, IUIKeyframeGraph graph, UIKeyframeSheet sheet, String bone)
    {
        Keyframe closest = bbsplus$getClosestKeyframe(sheet, cursor.getCursor());

        if (closest != null)
        {
            bbsplus$toggleSelection(graph, sheet, closest);
            cursor.setCursor((int) closest.getTick());
        }

        bbsplus$selectEditorBone(keyframeEditor, bone, true);
    }

    @Unique
    private static void bbsplus$addKeyframeAdditively(IUIKeyframeGraph graph, UIKeyframeSheet sheet, int tick)
    {
        Map<UIKeyframeSheet, List<Keyframe>> selection = bbsplus$captureSelection(graph);
        Keyframe keyframe = graph.addKeyframe(sheet, tick, null);

        bbsplus$restoreSelection(selection);
        bbsplus$addSelection(graph, sheet, keyframe);
    }

    @Unique
    private static Map<UIKeyframeSheet, List<Keyframe>> bbsplus$captureSelection(IUIKeyframeGraph graph)
    {
        Map<UIKeyframeSheet, List<Keyframe>> selection = new HashMap<>();

        for (UIKeyframeSheet sheet : graph.getSheets())
        {
            selection.put(sheet, new ArrayList<>(sheet.selection.getSelected()));
        }

        return selection;
    }

    @Unique
    private static void bbsplus$restoreSelection(Map<UIKeyframeSheet, List<Keyframe>> selection)
    {
        for (Map.Entry<UIKeyframeSheet, List<Keyframe>> entry : selection.entrySet())
        {
            UIKeyframeSheet sheet = entry.getKey();

            sheet.selection.clear();

            for (Keyframe keyframe : entry.getValue())
            {
                sheet.selection.add(keyframe);
            }
        }
    }

    @Unique
    private static void bbsplus$toggleSelection(IUIKeyframeGraph graph, UIKeyframeSheet sheet, Keyframe keyframe)
    {
        int index = sheet.channel.getKeyframes().indexOf(keyframe);

        if (index < 0)
        {
            return;
        }

        if (sheet.selection.has(index))
        {
            sheet.selection.remove(index);
            graph.pickSelected();
        }
        else
        {
            sheet.selection.add(index);
            graph.pickKeyframe(keyframe);
        }
    }

    @Unique
    private static void bbsplus$addSelection(IUIKeyframeGraph graph, UIKeyframeSheet sheet, Keyframe keyframe)
    {
        if (keyframe == null)
        {
            graph.pickSelected();

            return;
        }

        sheet.selection.add(keyframe);
        graph.pickKeyframe(keyframe);
    }

    @Unique
    private static Keyframe bbsplus$getClosestKeyframe(UIKeyframeSheet sheet, int tick)
    {
        KeyframeSegment segment = sheet.channel.find(tick);

        return segment != null ? segment.getClosest() : null;
    }

    @Unique
    private static Keyframe bbsplus$getKeyframeAt(UIKeyframeSheet sheet, int tick)
    {
        for (Object o : sheet.channel.getKeyframes())
        {
            Keyframe keyframe = (Keyframe) o;

            if ((int) keyframe.getTick() == tick)
            {
                return keyframe;
            }
        }

        return null;
    }

    @Unique
    private static UIKeyframeSheet bbsplus$resolveBoneSheet(IUIKeyframeGraph graph, String boneKey, String formPath)
    {
        UIKeyframeSheet sheet = graph.getSheet(boneKey);

        if (sheet == null)
        {
            for (UIKeyframeSheet candidate : graph.getSheets())
            {
                if (candidate.id != null && candidate.id.equalsIgnoreCase(boneKey))
                {
                    sheet = candidate;

                    break;
                }
            }
        }

        if (sheet != null)
        {
            if (sheet.channel.isEmpty())
            {
                UIKeyframeSheet poseSheet = bbsplus$getPreferredPoseSheet(graph, formPath);

                if (poseSheet != null)
                {
                    return poseSheet;
                }
            }

            return sheet;
        }

        return bbsplus$getPreferredPoseSheet(graph, formPath);
    }

    @Unique
    private static UIKeyframeSheet bbsplus$getPreferredPoseSheet(IUIKeyframeGraph graph, String formPath)
    {
        Keyframe selected = graph.getSelected();
        UIKeyframeSheet current = selected != null ? graph.getSheet(selected) : null;

        if (bbsplus$isPoseSheet(current, formPath))
        {
            return current;
        }

        UIKeyframeSheet last = graph.getLastSheet();

        if (bbsplus$isPoseSheet(last, formPath))
        {
            return last;
        }

        for (UIKeyframeSheet sheet : graph.getSheets())
        {
            if (bbsplus$isPoseSheet(sheet, formPath))
            {
                return sheet;
            }
        }

        return null;
    }

    @Unique
    private static boolean bbsplus$isPoseSheet(UIKeyframeSheet sheet, String formPath)
    {
        if (sheet == null || sheet.id == null)
        {
            return false;
        }

        String prefix = formPath.isEmpty() ? "" : formPath + FormUtils.PATH_SEPARATOR;

        return sheet.id.equals(prefix + "pose") || sheet.id.startsWith(prefix + "pose_overlay");
    }

    @Unique
    private static void bbsplus$selectEditorBone(UIKeyframeEditor keyframeEditor, String bone, boolean additive)
    {
        if (keyframeEditor.editor instanceof UIPoseKeyframeFactory poseFactory && poseFactory.poseEditor.hasBone(bone))
        {
            poseFactory.poseEditor.selectBone(bone, additive);
        }
    }
}
