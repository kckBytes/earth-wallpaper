#version 300 es
precision mediump float;
in float vA;
in float vDay;
uniform float uAlpha;
uniform vec3  uColor;
uniform int   uRound;                    // 1 = soft round dot (GL_POINTS)
uniform int   uHalo;                     // 1 = this pass is the dark outline under a streak (fades in with daylight)
uniform float uInk;                      // without a wide outline: darken the streak itself this much by day
out vec4 fragColor;
void main() {
    float a = vA * uAlpha;
    if (uRound == 1) { float d = length(gl_PointCoord - 0.5) * 2.0; a *= smoothstep(1.0, 0.15, d); }
    vec3 c = uColor;
    if (uHalo == 1) { c = vec3(0.0, 0.015, 0.05); a *= 0.80 * vDay; }
    else c = mix(c, vec3(0.03, 0.05, 0.12), uInk * vDay);
    fragColor = vec4(c * a, a);                          // premultiplied
}
