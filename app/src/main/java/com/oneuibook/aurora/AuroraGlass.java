package com.oneuibook.aurora;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewParent;
import android.widget.FrameLayout;

/**
 * Tri-Engine Optical Liquid Glass Renderer for Aurora Browser.
 * Combines:
 * 1) Kyant0/AndroidLiquidGlass: analytic rounded-rect SDF, circleMap spherical refraction with
 *    radial depthEffect, 7-band spectral chromatic dispersion (R/O/Y/G/C/B/V), vibrancy & inner shadow.
 * 2) iyinchao/liquid-glass-studio: Snell's law incidence refraction, 5th-power physical Fresnel,
 *    dual-peak opposing glare (45-deg primary + opposite bounce), and dark edge contrast framing.
 * 3) MeiloX/MeiloX: smooth-union metaball morphing (smin) for up to 3 shapes, steep outer optical lip,
 *    and Gaussian interactive touch bloom.
 */
final class AuroraGlass {
    private AuroraGlass() {}

    static boolean shadersOk = Build.VERSION.SDK_INT >= 33;

    static final String SHADER =
            "uniform shader content;\n" +
            "uniform float4 s0; uniform float s0r;\n" +
            "uniform float4 s1; uniform float s1r;\n" +
            "uniform float4 s2; uniform float s2r;\n" +
            "uniform float smoothK;\n" +
            "uniform float edgeW;\n" +
            "uniform float refr;\n" +
            "uniform float chroma;\n" +
            "uniform float4 tint;\n" +
            "uniform float rim;\n" +
            "uniform float alphaMul;\n" +
            "uniform float2 magC;\n" +
            "uniform float mag;\n" +
            "uniform float outerW;\n" +
            "uniform float outerRefr;\n" +
            "uniform float edgeDark;\n" +
            "uniform float vibrance;\n" +
            "uniform float4 press;\n" +
            "uniform float depthEff;\n" +
            "uniform float innerShad;\n" +
            "uniform float fresnelAmt;\n" +
            "float sdRR(float2 p, float4 r, float rad) {\n" +
            "  float2 hs = r.zw * 0.5;\n" +
            "  float rr = min(rad, min(hs.x, hs.y));\n" +
            "  float2 q = abs(p - (r.xy + hs)) - (hs - float2(rr));\n" +
            "  return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rr;\n" +
            "}\n" +
            "float2 gradSdRR(float2 cc, float2 hs, float rad) {\n" +
            "  float2 corner = abs(cc) - (hs - float2(rad));\n" +
            "  if (corner.x >= 0.0 || corner.y >= 0.0) {\n" +
            "    float2 mc = max(corner, float2(0.0));\n" +
            "    float ml = length(mc);\n" +
            "    return ml > 0.0001 ? sign(cc) * (mc / ml) : float2(0.0);\n" +
            "  } else {\n" +
            "    float gx = step(corner.y, corner.x);\n" +
            "    return sign(cc) * float2(gx, 1.0 - gx);\n" +
            "  }\n" +
            "}\n" +
            "float smin(float a, float b, float k) {\n" +
            "  if (k <= 0.001) { return min(a, b); }\n" +
            "  float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);\n" +
            "  return mix(b, a, h) - k * h * (1.0 - h);\n" +
            "}\n" +
            "float scene(float2 p) {\n" +
            "  float d = sdRR(p, s0, s0r);\n" +
            "  if (s1.z > 0.5) { d = smin(d, sdRR(p, s1, s1r), smoothK); }\n" +
            "  if (s2.z > 0.5) { d = smin(d, sdRR(p, s2, s2r), smoothK); }\n" +
            "  return d;\n" +
            "}\n" +
            "float circleMap(float x) {\n" +
            "  float c = clamp(x, 0.0, 1.0);\n" +
            "  return 1.0 - sqrt(max(0.0, 1.0 - c * c));\n" +
            "}\n" +
            "float4 look(float2 at) { return float4(content.eval(at)); }\n" +
            "half4 main(float2 p) {\n" +
            "  float d = scene(p);\n" +
            "  if (d > 1.5) { return half4(0.0); }\n" +
            "  float2 hs = max(s0.zw * 0.5, float2(1.0));\n" +
            "  float2 cc = p - (s0.xy + hs);\n" +
            "  bool single = (s1.z <= 0.5 && s2.z <= 0.5);\n" +
            "  float2 gRim = float2(0.0);\n" +
            "  float2 n = float2(0.0);\n" +
            "  if (single) {\n" +
            "    float gradR = min(s0r * 1.5, min(hs.x, hs.y));\n" +
            "    gRim = gradSdRR(cc, hs, gradR);\n" +
            "    float cl = length(cc);\n" +
            "    float2 radial = cl > 0.0001 ? (cc / cl) : float2(0.0);\n" +
            "    float2 comb = gRim + depthEff * radial;\n" +
            "    float cl2 = length(comb);\n" +
            "    n = cl2 > 0.0001 ? (comb / cl2) : gRim;\n" +
            "  } else {\n" +
            "    float2 g = float2(scene(p + float2(1.0, 0.0)) - scene(p - float2(1.0, 0.0)),\n" +
            "                      scene(p + float2(0.0, 1.0)) - scene(p - float2(0.0, 1.0)));\n" +
            "    float gl = length(g);\n" +
            "    gRim = gl > 0.0001 ? (g / gl) : float2(0.0);\n" +
            "    n = gRim;\n" +
            "  }\n" +
            "  float depth = max(-d, 0.0);\n" +
            "  float xR = clamp(1.0 - depth / max(edgeW, 0.001), 0.0, 1.0);\n" +
            "  float thI = asin(clamp(xR * xR, 0.0, 0.985));\n" +
            "  float thT = asin(sin(thI) / 1.48);\n" +
            "  float snell = clamp(-tan(thT - thI), 0.0, 1.4);\n" +
            "  float tLip = clamp(1.0 - depth / max(outerW, 0.001), 0.0, 1.0);\n" +
            "  float bend = refr * mix(circleMap(xR), snell, 0.55) + outerRefr * circleMap(tLip) * tLip;\n" +
            "  float2 q = magC + (p - magC) / mag;\n" +
            "  float2 off = n * bend;\n" +
            "  float2 refracted = q + off;\n" +
            "  float4 c = float4(0.0);\n" +
            "  if (chroma > 0.005 && bend > 0.05) {\n" +
            "    float cw = single\n" +
            "        ? clamp(abs((cc.x * cc.y) / max(hs.x * hs.y, 1.0)) * 1.5, 0.0, 1.0)\n" +
            "        : clamp(abs(gRim.x * gRim.y) * 2.0, 0.0, 1.0);\n" +
            "    float2 disp = off * (chroma * (0.18 + 0.82 * cw));\n" +
            "    float4 red    = look(refracted + disp);\n" +
            "    float4 orange = look(refracted + disp * 0.666667);\n" +
            "    float4 yellow = look(refracted + disp * 0.333333);\n" +
            "    float4 green  = look(refracted);\n" +
            "    float4 cyan   = look(refracted - disp * 0.333333);\n" +
            "    float4 blue   = look(refracted - disp * 0.666667);\n" +
            "    float4 purple = look(refracted - disp);\n" +
            "    c.r = (red.r + orange.r + yellow.r) / 3.5 + purple.r / 7.0;\n" +
            "    c.g = orange.g / 7.0 + (yellow.g + green.g + cyan.g) / 3.5;\n" +
            "    c.b = (cyan.b + blue.b + purple.b) / 3.0;\n" +
            "    c.a = (red.a + orange.a + yellow.a + green.a + cyan.a + blue.a + purple.a) / 7.0;\n" +
            "  } else {\n" +
            "    c = look(refracted);\n" +
            "  }\n" +
            "  float lum = dot(c.rgb, float3(0.2126, 0.7152, 0.0722));\n" +
            "  c.rgb = clamp(mix(float3(lum), c.rgb, vibrance), 0.0, 1.0);\n" +
            "  c = float4(mix(c.rgb, tint.rgb, tint.a), mix(c.a, 1.0, tint.a));\n" +
            "  float fresnel = pow(clamp(1.0 - depth / max(edgeW * 0.72, 2.0), 0.0, 1.0), 5.0);\n" +
            "  c.rgb = mix(c.rgb, float3(1.0), fresnel * fresnelAmt * 0.36);\n" +
            "  if (press.w > 0.001) {\n" +
            "    float2 k = (p - press.xy) / max(press.z, 1.0);\n" +
            "    float bloom = exp(-0.5 * dot(k, k)) * press.w;\n" +
            "    c.rgb = mix(c.rgb, c.rgb * 1.12 + float3(0.09), clamp(bloom + press.w * 0.22, 0.0, 1.0));\n" +
            "  }\n" +
            "  if (innerShad > 0.001) {\n" +
            "    float isBand = 1.0 - smoothstep(0.0, max(outerW * 2.2, 6.0), depth);\n" +
            "    c.rgb *= (1.0 - innerShad * 0.14 * isBand);\n" +
            "  }\n" +
            "  float ang = atan(gRim.y, gRim.x);\n" +
            "  float glareWave = 0.5 + 0.5 * sin((ang - 0.785398) * 2.0);\n" +
            "  float topBias = smoothstep(-0.3, 0.6, -dot(gRim, float2(0.7071, 0.7071)));\n" +
            "  float glare = pow(glareWave, 1.65) * mix(0.55, 1.15, topBias);\n" +
            "  float darkLine = smoothstep(-3.0, -1.1, d) * (1.0 - smoothstep(-1.1, 0.25, d));\n" +
            "  c.rgb *= (1.0 - edgeDark * darkLine * (1.0 - glare * 0.8));\n" +
            "  float rimMask = smoothstep(-3.8, -0.15, d) * (1.0 - smoothstep(0.0, 1.0, d));\n" +
            "  c.rgb = clamp(c.rgb + float3(rim * rimMask * (0.20 + 0.90 * glare)), 0.0, 1.0);\n" +
            "  float a = clamp(0.5 - d, 0.0, 1.0) * alphaMul;\n" +
            "  return half4(c * a);\n" +
            "}\n";

    static RuntimeShader newShader() {
        if (!shadersOk) return null;
        try {
            return new RuntimeShader(SHADER);
        } catch (Throwable t) {
            try {
                return new RuntimeShader(SHADER.replace("content.eval(at)", "sample(content, at)"));
            } catch (Throwable t2) {
                shadersOk = false;
                return null;
            }
        }
    }

    /** Wraps the live browser viewport (WebView + StartPage) so floating Safari bars refract live content. */
    static final class Backdrop extends FrameLayout {
        final RenderNode node = new RenderNode("aurora-backdrop");
        boolean recorded;

        Backdrop(Context c) { super(c); }

        @Override public void draw(Canvas canvas) {
            if (!canvas.isHardwareAccelerated() || getWidth() <= 0 || getHeight() <= 0) {
                super.draw(canvas);
                recorded = false;
                return;
            }
            node.setPosition(0, 0, getWidth(), getHeight());
            RecordingCanvas rc = node.beginRecording(getWidth(), getHeight());
            try {
                super.draw(rc);
            } finally {
                node.endRecording();
            }
            recorded = true;
            canvas.drawRenderNode(node);
        }
    }

    /** One optical glass surface composed of up to 3 smooth-unioned rounded rectangles. */
    static final class Surface {
        final RectF a = new RectF(), b = new RectF(), c = new RectF();
        float ra, rb, rc;
        float smoothK;
        float blurDp = 12f;
        float refractDp = 14f, edgeDp = 22f, chroma = 0.28f, rim = 0.34f;
        float outerDp = 6f, outerRefractDp = 9f, edgeDark = 0.24f, vibrance = 1.32f;
        float depthEffect = 0.40f, innerShadow = 0f, fresnelAmt = 0.52f;
        float mag = 1f, magX, magY;
        float pressX, pressY, pressR, pressAmt;
        int tint = Ui.barTint();
        int strokeColor = Ui.glassStroke();
        float alpha = 1f;
        float shadowAlpha = 0.16f;
        boolean blur = true;
        RenderNode extra;

        private final RenderNode node = new RenderNode("aurora-glass");
        private final RuntimeShader shader = AuroraGlass.newShader();
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF tmp = new RectF();
        private RenderEffect blurEffect, cachedFx;
        private float lastBlur = -1f;
        private int lastSig;

        Surface() {
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(1f, Ui.dpf(0.65f)));
        }

        void shapes(RectF r0, float rad0, RectF r1, float rad1, RectF r2, float rad2) {
            if (r0 != null) { a.set(r0); ra = rad0; } else a.setEmpty();
            if (r1 != null) { b.set(r1); rb = rad1; } else b.setEmpty();
            if (r2 != null) { c.set(r2); rc = rad2; } else c.setEmpty();
        }

        Path outline() {
            path.reset();
            if (!a.isEmpty()) path.addRoundRect(a, Math.min(ra, a.height() / 2f), Math.min(ra, a.height() / 2f), Path.Direction.CW);
            if (!b.isEmpty()) path.addRoundRect(b, Math.min(rb, b.height() / 2f), Math.min(rb, b.height() / 2f), Path.Direction.CW);
            if (!c.isEmpty()) path.addRoundRect(c, Math.min(rc, c.height() / 2f), Math.min(rc, c.height() / 2f), Path.Direction.CW);
            return path;
        }

        void draw(Canvas canvas, View host, Backdrop backdrop) {
            if (alpha <= 0.005f || a.isEmpty()) return;
            outline();
            if (shadowAlpha > 0f) {
                fill.setColor(0x01FFFFFF);
                fill.setShadowLayer(Ui.dpf(16), 0, Ui.dpf(5), Ui.alpha(0xFF000000, shadowAlpha * alpha * (Ui.isDarkSurface() ? 2.1f : 1f)));
                canvas.drawPath(path, fill);
                fill.clearShadowLayer();
            }
            boolean hw = canvas.isHardwareAccelerated() && ((backdrop != null && backdrop.recorded) || extra != null);
            if (hw) {
                float pad = Ui.dpf(blurDp) * 1.8f + Ui.dpf(refractDp + outerRefractDp);
                tmp.set(a);
                if (!b.isEmpty()) tmp.union(b);
                if (!c.isEmpty()) tmp.union(c);
                float[] off = backdrop != null ? offsetIn(host, backdrop) : new float[]{0f, 0f};
                int w = Math.max(1, (int) Math.ceil(tmp.width() + pad * 2f));
                int h = Math.max(1, (int) Math.ceil(tmp.height() + pad * 2f));
                float ox = tmp.left - pad, oy = tmp.top - pad;
                node.setPosition(0, 0, w, h);

                boolean useShader = Ui.liquidGlass && shader != null && AuroraGlass.shadersOk;
                float br = Ui.liquidGlass ? (blur ? Ui.dpf(blurDp) : 0f) : Ui.dpf(Math.max(blurDp, 22f));
                RenderEffect fx = null;
                if (br > 0.5f) {
                    if (br != lastBlur || blurEffect == null) {
                        blurEffect = RenderEffect.createBlurEffect(br, br, Shader.TileMode.CLAMP);
                        lastBlur = br;
                    }
                    fx = blurEffect;
                }
                if (useShader) {
                    int sig = signature(-ox, -oy, br);
                    if (sig != lastSig || cachedFx == null) {
                        setUniforms(-ox, -oy);
                        RenderEffect sh = RenderEffect.createRuntimeShaderEffect(shader, "content");
                        cachedFx = fx == null ? sh : RenderEffect.createChainEffect(sh, fx);
                        lastSig = sig;
                        node.setRenderEffect(cachedFx);
                    }
                } else {
                    node.setRenderEffect(fx);
                    lastSig = 0;
                    cachedFx = null;
                }

                RecordingCanvas rc = node.beginRecording(w, h);
                try {
                    rc.translate(-ox, -oy);
                    if (backdrop != null && backdrop.recorded) {
                        rc.save();
                        rc.translate(-off[0], -off[1]);
                        rc.drawRenderNode(backdrop.node);
                        rc.restore();
                    }
                    if (extra != null) rc.drawRenderNode(extra);
                } finally {
                    node.endRecording();
                }

                canvas.save();
                if (!useShader) canvas.clipPath(path);
                canvas.translate(ox, oy);
                if (!useShader) {
                    node.setAlpha(alpha);
                    canvas.drawRenderNode(node);
                    canvas.restore();
                    fill.setColor(Ui.alpha(tint, alpha));
                    canvas.drawPath(path, fill);
                } else {
                    node.setAlpha(1f);
                    canvas.drawRenderNode(node);
                    canvas.restore();
                }
            } else {
                int base = Ui.mix(Ui.bg(), tint | 0xFF000000, 0.72f);
                fill.setColor(Ui.alpha(base, 0.94f * alpha));
                canvas.drawPath(path, fill);
            }

            if (strokeColor != 0 && smoothK <= 0.5f) {
                tmp.set(a);
                if (!b.isEmpty()) tmp.union(b);
                if (!c.isEmpty()) tmp.union(c);
                stroke.setShader(new LinearGradient(tmp.left, tmp.top, tmp.right, tmp.bottom,
                        Ui.alpha(Ui.isDarkSurface() ? 0x48FFFFFF : 0xCCFFFFFF, alpha),
                        Ui.alpha(strokeColor, alpha), Shader.TileMode.CLAMP));
                canvas.drawPath(path, stroke);
                stroke.setShader(null);
            }
        }

        private int signature(float dx, float dy, float br) {
            float[] v = {a.left + dx, a.top + dy, a.right + dx, a.bottom + dy, ra,
                    b.left + dx, b.top + dy, b.right + dx, b.bottom + dy, rb,
                    c.left + dx, c.top + dy, c.right + dx, c.bottom + dy, rc,
                    smoothK, refractDp, edgeDp, chroma, rim, alpha, mag, magX + dx, magY + dy,
                    outerDp, outerRefractDp, edgeDark, vibrance, depthEffect, innerShadow, fresnelAmt,
                    pressX + dx, pressY + dy, pressR, pressAmt, br, tint, Ui.isDarkSurface() ? 1 : 0};
            int h = 17;
            for (float f : v) h = h * 31 + Math.round(f * 4f);
            return h;
        }

        private void setUniforms(float dx, float dy) {
            RuntimeShader s = shader;
            s.setFloatUniform("s0", a.left + dx, a.top + dy, a.width(), a.height());
            s.setFloatUniform("s0r", Math.min(ra, a.height() / 2f));
            if (!b.isEmpty()) {
                s.setFloatUniform("s1", b.left + dx, b.top + dy, b.width(), b.height());
                s.setFloatUniform("s1r", Math.min(rb, b.height() / 2f));
            } else { s.setFloatUniform("s1", 0f, 0f, 0f, 0f); s.setFloatUniform("s1r", 0f); }
            if (!c.isEmpty()) {
                s.setFloatUniform("s2", c.left + dx, c.top + dy, c.width(), c.height());
                s.setFloatUniform("s2r", Math.min(rc, c.height() / 2f));
            } else { s.setFloatUniform("s2", 0f, 0f, 0f, 0f); s.setFloatUniform("s2r", 0f); }
            s.setFloatUniform("smoothK", smoothK);
            s.setFloatUniform("edgeW", Ui.dpf(edgeDp));
            s.setFloatUniform("refr", Ui.dpf(refractDp));
            s.setFloatUniform("chroma", chroma);
            float ta = ((tint >>> 24) / 255f);
            s.setFloatUniform("tint", ((tint >> 16) & 255) / 255f, ((tint >> 8) & 255) / 255f, (tint & 255) / 255f, ta);
            s.setFloatUniform("rim", rim);
            s.setFloatUniform("alphaMul", alpha);
            s.setFloatUniform("magC", (magX == 0 ? a.centerX() : magX) + dx, (magY == 0 ? a.centerY() : magY) + dy);
            s.setFloatUniform("mag", Math.max(0.2f, mag));
            s.setFloatUniform("outerW", Ui.dpf(outerDp));
            s.setFloatUniform("outerRefr", Ui.dpf(outerRefractDp));
            s.setFloatUniform("edgeDark", Ui.isDarkSurface() ? edgeDark * 0.42f : edgeDark);
            s.setFloatUniform("vibrance", vibrance);
            s.setFloatUniform("press", pressX + dx, pressY + dy, Math.max(1f, pressR), pressAmt);
            s.setFloatUniform("depthEff", depthEffect);
            s.setFloatUniform("innerShad", innerShadow);
            s.setFloatUniform("fresnelAmt", fresnelAmt);
        }
    }

    static float[] offsetIn(View v, View backdrop) {
        float[] a = abs(v), b = abs(backdrop);
        return new float[]{a[0] - b[0], a[1] - b[1]};
    }

    static float[] abs(View v) {
        float x = 0, y = 0;
        View cur = v;
        while (cur != null) {
            x += cur.getLeft() + cur.getTranslationX();
            y += cur.getTop() + cur.getTranslationY();
            ViewParent p = cur.getParent();
            if (!(p instanceof View)) break;
            View pv = (View) p;
            x -= pv.getScrollX();
            y -= pv.getScrollY();
            cur = pv;
        }
        return new float[]{x, y};
    }
}
