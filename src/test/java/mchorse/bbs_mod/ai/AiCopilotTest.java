package mchorse.bbs_mod.ai;

import mchorse.bbs_mod.ai.curve.CurvePolisher;
import mchorse.bbs_mod.ai.curve.CurveSnapshots;
import mchorse.bbs_mod.ai.curve.CurveSnapshots.Snapshot;
import mchorse.bbs_mod.ai.curve.PolishKind;
import mchorse.bbs_mod.ai.curve.PolishOp;
import mchorse.bbs_mod.ai.curve.SmoothingKernel;
import mchorse.bbs_mod.ai.plan.AnimationPlan;
import mchorse.bbs_mod.data.DataToString;
import mchorse.bbs_mod.utils.interps.Interpolations;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.factories.FloatKeyframeFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Standalone sanity checks for the AI copilot's local milestones
 * ({@code java -cp "build/classes/java/main;build/classes/java/test" mchorse.bbs_mod.ai.AiCopilotTest}).
 * No Minecraft, no network: M2 curve polish, the AnimationPlan contract and
 * error classification. The acceptance bar that matters most: the same keys
 * plus the same intents produce bitwise identical handles on every run.
 */
public class AiCopilotTest
{
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception
    {
        curveDeterminism();
        curveEaseMappings();
        curveHoldSnap();
        curveElasticOvershoot();
        curveSmoothLinearArc();
        curveNonNumericSkipped();
        smoothingKernel();
        snapshots();
        planContract();
        errorClassification();
        jsonParsing();

        System.out.println("\n" + (failures == 0 ? "ALL PASS" : failures + " FAILURES") + " (" + checks + " checks)");

        if (failures > 0)
        {
            System.exit(1);
        }
    }

    private static List<Keyframe<Float>> sample()
    {
        FloatKeyframeFactory factory = new FloatKeyframeFactory();
        List<Keyframe<Float>> keys = new ArrayList<>();

        keys.add(new Keyframe<>("k0", factory, 0F, 0F));
        keys.add(new Keyframe<>("k1", factory, 10F, 10F));
        keys.add(new Keyframe<>("k2", factory, 30F, 4F));

        return keys;
    }

    /** Same input, bitwise same output - run the polish twice into fresh copies and compare fields. */
    private static void curveDeterminism()
    {
        FloatKeyframeFactory factory = new FloatKeyframeFactory();
        List<PolishOp> ops = List.of(new PolishOp(PolishKind.EASE_IN_OUT, 0.7F, 10F, 30F), new PolishOp(PolishKind.OVERSHOOT, 0.4F));

        List<Keyframe<Float>> first = CurvePolisher.polished(sample(), factory, ops);
        List<Keyframe<Float>> second = CurvePolisher.polished(sample(), factory, ops);

        equal(first.size(), second.size(), "determinism: same key count");

        for (int i = 0; i < first.size(); i++)
        {
            Keyframe<Float> a = first.get(i);
            Keyframe<Float> b = second.get(i);

            equal(a.getTick(), b.getTick(), "determinism: tick " + i);
            equal(a.getValue(), b.getValue(), "determinism: value " + i);
            equal(a.getInterpolation().getInterp(), b.getInterpolation().getInterp(), "determinism: interp " + i);
            equal(a.lx, b.lx, "determinism: lx " + i);
            equal(a.ly, b.ly, "determinism: ly " + i);
            equal(a.rx, b.rx, "determinism: rx " + i);
            equal(a.ry, b.ry, "determinism: ry " + i);
            equal(a.getDuration(), b.getDuration(), "determinism: duration " + i);
        }
    }

    private static void curveEaseMappings()
    {
        FloatKeyframeFactory factory = new FloatKeyframeFactory();

        /* EASE_OUT on k0 shapes segment k0 -> k1 (w=10): k0.rx = 0.42*max(s,.05)*w with s=.5 -> 2.1 */
        List<Keyframe<Float>> keys = CurvePolisher.polished(sample(), factory, List.of(new PolishOp(PolishKind.EASE_OUT, 0.5F, 0F, 0F)));

        check(keys.get(0).getInterpolation().has(Interpolations.BEZIER), "ease_out sets BEZIER");
        near(keys.get(0).rx, 2.1F, "ease_out: k0.rx = x1*w");
        near(keys.get(1).lx, 4.2F, "ease_out: next.lx = (1-0.58)*w");
        near(keys.get(0).ry, 0F, "ease_out: flat y handle");

        /* EASE_IN on k1 shapes segment k0 -> k1: k0.rx = 0.42*10 = 4.2, k1.lx = (1-x2)*10, x2 = 1-0.42*max(.7,.05) = .706 */
        keys = CurvePolisher.polished(sample(), factory, List.of(new PolishOp(PolishKind.EASE_IN, 0.7F, 10F, 10F)));

        check(keys.get(1).getInterpolation().has(Interpolations.BEZIER), "ease_in sets BEZIER");
        near(keys.get(0).rx, 4.2F, "ease_in: prev.rx = 0.42*w");
        near(keys.get(1).lx, (1F - 0.706F) * 10F, "ease_in: k.lx = (1-x2)*w");

        /* Handles never escape [0, w]: extreme strength on a 10-tick segment */
        keys = CurvePolisher.polished(sample(), factory, List.of(new PolishOp(PolishKind.EASE_IN_OUT, 1F)));

        for (Keyframe<Float> key : keys)
        {
            check(key.lx >= 0F && key.lx <= 30F && key.rx >= 0F && key.rx <= 30F, "handles clamped to segment");
        }

        /* LINEAR zeroes everything */
        keys = CurvePolisher.polished(sample(), factory, List.of(new PolishOp(PolishKind.LINEAR)));

        for (Keyframe<Float> key : keys)
        {
            check(key.getInterpolation().has(Interpolations.LINEAR), "linear sets LINEAR");
            equal(0F, key.lx + key.ly + key.rx + key.ry, "linear zeroes handles");
        }
    }

    private static void curveHoldSnap()
    {
        FloatKeyframeFactory factory = new FloatKeyframeFactory();

        List<Keyframe<Float>> keys = CurvePolisher.polished(sample(), factory, List.of(new PolishOp(PolishKind.HOLD, 0.25F, 10F, 10F)));

        check(keys.get(1).getInterpolation().has(Interpolations.CONST), "hold sets CONST");
        equal(5F, keys.get(1).getDuration(), "hold: forced duration = strength*gap");

        /* SNAP: incoming segment front-loads, target holds constant */
        keys = CurvePolisher.polished(sample(), factory, List.of(new PolishOp(PolishKind.SNAP, 0.5F, 30F, 30F)));

        check(keys.get(2).getInterpolation().has(Interpolations.CONST), "snap: target CONST");
        equal(0F, keys.get(2).getDuration(), "snap: no forced duration");
        near(keys.get(1).rx, (1F - 0.325F) * 20F, "snap: prev.rx compressed attack");
        near(keys.get(1).ry, 4F - 10F, "snap: prev.ry = full height");
    }

    private static void curveElasticOvershoot()
    {
        FloatKeyframeFactory factory = new FloatKeyframeFactory();

        List<Keyframe<Float>> keys = CurvePolisher.polished(sample(), factory, List.of(new PolishOp(PolishKind.ELASTIC, 0.8F)));

        check(keys.get(1).getInterpolation().has(Interpolations.ELASTIC_OUT), "elastic -> ELASTIC_OUT");
        near(-4.8F, (float) keys.get(1).getInterpolation().getV1(), "elastic: v1 = -6*strength");

        keys = CurvePolisher.polished(sample(), factory, List.of(new PolishOp(PolishKind.OVERSHOOT, 0.5F)));

        check(keys.get(1).getInterpolation().has(Interpolations.BACK_OUT), "overshoot -> BACK_OUT");
        near(1.5F, (float) keys.get(1).getInterpolation().getV1(), "overshoot: v1 = 3*strength");
    }

    private static void curveSmoothLinearArc()
    {
        FloatKeyframeFactory factory = new FloatKeyframeFactory();

        List<Keyframe<Float>> keys = CurvePolisher.polished(sample(), factory, List.of(new PolishOp(PolishKind.SMOOTH)));

        for (Keyframe<Float> key : keys)
        {
            check(key.getInterpolation().has(Interpolations.AUTO_CLAMPED), "smooth -> AUTO_CLAMPED");
        }

        /* ARC inserts one intermediate frame per interior segment (strength .5) */
        keys = CurvePolisher.polished(sample(), factory, List.of(new PolishOp(PolishKind.ARC, 0.5F)));

        equal(5, keys.size(), "arc: 3 keys + 2 inserted");
        near(5F, keys.get(1).getTick(), "arc: mid tick of first segment");
        near(20F, keys.get(3).getTick(), "arc: mid tick of second segment");

        /* Bulge on segment 0->10 (h=10, w=10): mid value 5 + 0.5*(2.5+1) = 6.75 */
        near(6.75F, keys.get(1).getValue(), "arc: parabola bulge");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void curveNonNumericSkipped()
    {
        mchorse.bbs_mod.utils.keyframes.factories.LinkKeyframeFactory link = new mchorse.bbs_mod.utils.keyframes.factories.LinkKeyframeFactory();
        mchorse.bbs_mod.utils.keyframes.factories.StringKeyframeFactory string = new mchorse.bbs_mod.utils.keyframes.factories.StringKeyframeFactory();

        check(!CurvePolisher.isPolishable(link), "link factory is not polishable");
        check(!CurvePolisher.isPolishable(string), "string factory is not polishable");
        check(CurvePolisher.isPolishable(new FloatKeyframeFactory()), "float factory is polishable");

        /* A link channel's keys must survive polish untouched */
        List<Keyframe<mchorse.bbs_mod.resources.Link>> linkKeys = new ArrayList<>();

        linkKeys.add(new Keyframe<>("l0", link, 0F, mchorse.bbs_mod.resources.Link.create("a.png")));
        linkKeys.add(new Keyframe<>("l1", link, 10F, mchorse.bbs_mod.resources.Link.create("b.png")));
        CurvePolisher.polish((List) linkKeys, List.of(new PolishOp(PolishKind.EASE_IN_OUT, 0.5F), new PolishOp(PolishKind.ARC, 0.5F)));
        equal(2, linkKeys.size(), "non-numeric channel untouched");
        equal("assets:a.png", linkKeys.get(0).getValue().toString(), "non-numeric value intact");
    }

    private static void smoothingKernel()
    {
        List<Keyframe<Float>> keys = sample();

        SmoothingKernel.smooth(keys, 0);
        SmoothingKernel.smooth(keys, 1);

        /* Triangular smoothing of a monotone ramp keeps endpoints and midpoints ordered */
        for (int i = 1; i < keys.size(); i++)
        {
            check(keys.get(i).getTick() > keys.get(i - 1).getTick(), "smoothing preserves ticks");
        }

        equal(0F, keys.get(0).getValue(), "smoothing keeps left endpoint");
        equal(4F, keys.get(keys.size() - 1).getValue(), "smoothing keeps right endpoint");
    }

    private static void snapshots()
    {
        FloatKeyframeFactory factory = new FloatKeyframeFactory();
        List<Keyframe<Float>> keys = sample();
        Snapshot before = CurveSnapshots.capture(keys);

        CurvePolisher.polish(keys, List.of(new PolishOp(PolishKind.EASE_IN_OUT, 0.9F)));

        check(!CurveSnapshots.matches(before, keys), "snapshot detects polish");
        check(CurveSnapshots.restore(before, keys), "restore succeeds on same size");
        check(CurveSnapshots.matches(before, keys), "restore is bitwise");
        check(!CurveSnapshots.restore(before, new ArrayList<>()), "restore refuses size change");
    }

    private static void planContract() throws Exception
    {
        String json = """
            {
              "version": 1, "fps": 20, "total_ticks": 60,
              "character": { "model_hint": "humanoid", "bone_map_confirmed": false },
              "beats": [
                { "index": 0, "tick": 0, "phase": "contact", "pose": "crouch", "spacing": 0, "intents": ["hold"] },
                { "index": 1, "tick": 6, "phase": "down", "pose": "compress", "spacing": 6, "intents": ["ease_out"] },
                { "index": 2, "tick": 28, "phase": "contact", "pose": "punch", "spacing": 22, "intents": ["snap", "impact"] }
              ],
              "notes": "test plan"
            }
            """;

        AnimationPlan plan = AnimationPlan.parse(json);

        equal(3, plan.beats.size(), "plan: beats parsed");
        equal(PolishKind.IMPACT, plan.beats.get(2).intents.get(1), "plan: intent label parsed");

        /* Contract violations must be loud */
        String[] bad = {
            "{\"version\":9,\"total_ticks\":1,\"beats\":[{\"tick\":0,\"phase\":\"contact\",\"pose\":\"idle\"}]}",
            "{\"version\":1,\"total_ticks\":1,\"beats\":[{\"tick\":0,\"phase\":\"jump\",\"pose\":\"idle\"}]}",
            "{\"version\":1,\"total_ticks\":1,\"beats\":[{\"tick\":0,\"phase\":\"contact\",\"pose\":\"flying\"}]}",
            "{\"version\":1,\"total_ticks\":1,\"beats\":[{\"tick\":0,\"phase\":\"contact\",\"pose\":\"idle\"},{\"tick\":0,\"phase\":\"contact\",\"pose\":\"idle\"}]}",
            "{\"version\":1,\"total_ticks\":1,\"beats\":[{\"tick\":0,\"phase\":\"contact\",\"pose\":\"idle\",\"intents\":[\"wiggle\"]}]}"
        };

        for (String broken : bad)
        {
            try
            {
                AnimationPlan.parse(broken);
                fail("plan: malformed plan accepted -> " + broken.substring(0, 40));
            }
            catch (AiException e)
            {
                check(e.type == AiException.Type.PARSE, "plan: violation is typed PARSE");
            }
        }

        /* Round trip through our own serializer */
        AnimationPlan reparsed = AnimationPlan.parse(DataToString.toString(plan.toData(), true));

        equal(plan.beats.size(), reparsed.beats.size(), "plan: round trip beat count");
    }

    private static void errorClassification() throws Exception
    {
        equal(AiException.Type.AUTH, AiException.fromHttp(401, null, "").type, "401 -> AUTH");
        equal(AiException.Type.AUTH, AiException.fromHttp(403, null, "").type, "403 -> AUTH");
        equal(AiException.Type.TIMEOUT, AiException.fromHttp(408, null, "").type, "408 -> TIMEOUT");
        equal(AiException.Type.CONTEXT_OVERFLOW, AiException.fromHttp(413, null, "").type, "413 -> CONTEXT");

        AiException rate = AiException.fromHttp(429, "7", "");

        equal(AiException.Type.RATE_LIMIT, rate.type, "429 -> RATE_LIMIT");
        equal(7000L, rate.retryAfterMs, "Retry-After seconds parsed to ms");
        check(rate.isRetryable(), "rate limit is retryable");
        check(!AiException.fromHttp(401, null, "").isRetryable(), "auth is not retryable");
        equal(AiException.Type.NETWORK, AiException.fromHttp(503, null, "").type, "5xx -> NETWORK");
        equal(AiException.Type.CONTENT_REJECTED, AiException.fromHttp(400, null, "{\"error\":{\"code\":\"content_filter\"}}").type, "400+filter -> REJECTED");
        equal(AiException.Type.CONTEXT_OVERFLOW, AiException.fromHttp(400, null, "maximum context length exceeded").type, "400+context -> CONTEXT");
    }

    private static void jsonParsing()
    {
        /* The repo's own parser must eat a real chat-completions body */
        mchorse.bbs_mod.data.types.MapType map = DataToString.mapFromString("""
            {"id":"chatcmpl-1","choices":[{"index":0,"message":{"role":"assistant","content":"pong"}}],"usage":{"prompt_tokens":11,"completion_tokens":1}}
            """);

        check(map != null, "chat body parses");
        equal("pong", map.get("choices").asList().get(0).asMap().get("message").asMap().getString("content"), "content extracted");
    }

    /* --- tiny check harness, same spirit as the repo's other sanity mains --- */

    private static void check(boolean condition, String label)
    {
        checks++;

        if (!condition)
        {
            failures++;
            System.out.println("FAIL: " + label);
        }
    }

    private static void equal(Object expected, Object actual, String label)
    {
        checks++;

        if (!java.util.Objects.equals(expected, actual))
        {
            failures++;
            System.out.println("FAIL: " + label + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void near(float expected, float actual, String label)
    {
        checks++;

        if (Math.abs(expected - actual) > 0.001F)
        {
            failures++;
            System.out.println("FAIL: " + label + " (expected " + expected + ", got " + actual + ")");
        }
    }

    private static void fail(String label)
    {
        checks++;
        failures++;
        System.out.println("FAIL: " + label);
    }
}
