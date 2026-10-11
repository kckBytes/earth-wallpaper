#version 300 es
precision mediump float;
in float vA;
in float vDay;
uniform float uAlpha;
uniform vec3  uColor;
uniform int   uRound;                    // 1 = soft round dot (GL_POINTS)
uniform int   uHalo;                     // 1 = outline pass (drawn nudged a pixel each way, under the line)
uniform float uInk;                      // 1 = wind streaks: dark ink by day, white glow by night; 0 = dots keep their colour
out vec4 fragColor;
void main() {
    float a = vA * uAlpha;
    if (uRound == 1) { float d = length(gl_PointCoord - 0.5) * 2.0; a *= smoothstep(1.0, 0.15, d); }
    float day = uInk * vDay;
    vec3 c;
    if (uHalo == 1) {
        // the outline: light by day (so dark streaks show over dark ocean too), none at night
        c = vec3(0.92, 0.95, 1.0);
        a *= 0.50 * day;
    } else {
        // the line: white glow at night, near-black ink by day (twilight blends the two)
        c = mix(uColor, vec3(0.015, 0.025, 0.06), day);
        a *= mix(1.0, 1.45, day);
    }
    fragColor = vec4(c * min(a, 1.0), min(a, 1.0));      // premultiplied
}
