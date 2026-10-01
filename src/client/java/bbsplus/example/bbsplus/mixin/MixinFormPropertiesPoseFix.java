package bbsplus.example.bbsplus.mixin;

import bbsplus.example.bbsplus.pose.PoseBonePath;
import mchorse.bbs_mod.film.replays.FormProperties;
import mchorse.bbs_mod.film.replays.tracks.TrackId;
import mchorse.bbs_mod.film.replays.tracks.TrackKind;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.utils.MathUtils;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import mchorse.bbs_mod.utils.keyframes.factories.IKeyframeFactory;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Mixin(FormProperties.class)
public abstract class MixinFormPropertiesPoseFix
{
    private static final String BBSPLUS_POSE_PROPERTY = "pose";

    @Shadow
    @Final
    public Map<TrackId, KeyframeChannel> tracks;

    @Inject(method = "applyProperties(Lmchorse/bbs_mod/forms/forms/Form;FF)V", at = @At("RETURN"))
    private void bbsplus$resolvePoseFixPriority(Form form, float tick, float blend, CallbackInfo ci)
    {
        if (form == null)
        {
            return;
        }

        float clampedBlend = MathUtils.clamp(blend, 0F, 1F);
        Map<String, Map<String, Float>> fixes = new HashMap<>();
        Set<String> formPaths = new HashSet<>();

        for (Map.Entry<TrackId, KeyframeChannel> entry : this.tracks.entrySet())
        {
            TrackId trackId = entry.getKey();
            KeyframeChannel channel = entry.getValue();

            if (!trackId.is(TrackKind.BONE))
            {
                continue;
            }

            PoseBonePath path = new PoseBonePath(trackId.formPath(), trackId.subject());

            formPaths.add(path.formPath);

            KeyframeSegment segment = channel.find(tick);

            if (segment == null)
            {
                continue;
            }

            Object value = this.bbsplus$interpolateValue(channel, new PoseTransform(), segment, clampedBlend);

            if (value instanceof PoseTransform poseTransform)
            {
                fixes.computeIfAbsent(path.formPath, (key) -> new HashMap<>()).put(path.bone, poseTransform.fix);
            }
        }

        for (String formPath : formPaths)
        {
            TrackId poseTrackId = TrackId.property(formPath, BBSPLUS_POSE_PROPERTY);
            KeyframeChannel poseChannel = this.tracks.get(poseTrackId);

            if (poseChannel == null)
            {
                continue;
            }

            KeyframeSegment segment = poseChannel.find(tick);

            if (segment == null)
            {
                continue;
            }

            Form targetForm = FormUtils.getForm(form, formPath);

            if (!(targetForm instanceof ModelForm modelForm))
            {
                continue;
            }

            Object value = this.bbsplus$interpolateValue(poseChannel, modelForm.pose.getOriginalValue(), segment, clampedBlend);

            if (!(value instanceof Pose pose))
            {
                continue;
            }

            Map<String, Float> formFixes = fixes.computeIfAbsent(formPath, (key) -> new HashMap<>());

            for (Map.Entry<String, PoseTransform> poseEntry : pose.transforms.entrySet())
            {
                formFixes.put(poseEntry.getKey(), poseEntry.getValue().fix);
            }
        }

        for (Map.Entry<String, Map<String, Float>> formEntry : fixes.entrySet())
        {
            Form targetForm = FormUtils.getForm(form, formEntry.getKey());

            if (!(targetForm instanceof ModelForm modelForm))
            {
                continue;
            }

            Pose runtimePose = modelForm.pose.get();

            for (Map.Entry<String, Float> boneEntry : formEntry.getValue().entrySet())
            {
                runtimePose.get(boneEntry.getKey()).fix = boneEntry.getValue();
            }
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Object bbsplus$interpolateValue(KeyframeChannel channel, Object current, KeyframeSegment segment, float blend)
    {
        if (blend < 1F)
        {
            IKeyframeFactory factory = channel.getFactory();
            Object v = factory.copy(current);
            Object a = factory.copy(segment.createInterpolated());
            Object interpolated = factory.interpolate(v, v, a, a, Interpolations.LINEAR, blend);

            return factory.copy(interpolated);
        }

        return segment.createInterpolated();
    }
}
