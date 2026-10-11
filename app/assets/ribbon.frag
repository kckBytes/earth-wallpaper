#version 300 es
precision mediump float;
// One pass per pixel: how far it is from the streak's centre line decides core (ink / glow) or outline.
in float vA, vDay, vD, vCore, vOut;
uniform float uAlpha;                     // overall streak opacity
uniform float uDark;                      // 0..1: how dark the core goes by day (1 = near-black ink)
uniform float uOutA;                      // outline strength by day
out vec4 fragColor;
void main() {
    float r = abs(vD);
    float core = 1.0 - smoothstep(vCore - 0.6, vCore + 0.6, r);
    float ring = (1.0 - smoothstep(vCore + vOut - 0.6, vCore + vOut + 0.6, r)) - core;
    vec3 coreCol = mix(vec3(1.0), vec3(0.015, 0.025, 0.06), vDay * uDark);   // white glow at night, ink by day
    float aCore = core * vA * uAlpha * mix(1.0, 1.45, vDay);
    float aRing = max(ring, 0.0) * vA * uAlpha * uOutA * vDay;
    vec3 c = coreCol * aCore + vec3(0.92, 0.95, 1.0) * aRing;               // premultiplied; the two never overlap
    float a = min(aCore + aRing, 1.0);
    fragColor = vec4(c, a);
}
