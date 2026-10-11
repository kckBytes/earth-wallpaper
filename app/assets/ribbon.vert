#version 300 es
// Wind streaks as ribbons: every trail segment (point A -> point B) becomes a thin quad, built here on the GPU
// from a 4-corner template (instanced: one instance per segment). Width is computed per vertex, so it does
// not depend on the GPU's optional wide-line support: about 1.6 px at night, ~3.5 px by day (tunable).
layout(location = 0) in vec2 aCorner;     // x: 0 at A, 1 at B; y: -1 / +1 = left / right edge
layout(location = 1) in vec4 aA;          // segment start: unit-sphere ECEF xyz, w = opacity
layout(location = 2) in vec4 aB;          // segment end
uniform mat3  uView;                      // columns = screen right, up, toward camera (ECEF)
uniform vec2  uCenter;                    // globe centre, px
uniform float uRadius, uCamDist;
uniform vec2  uRes;
uniform vec3  uSun;                       // toward the Sun (ECEF)
uniform int   uK;                         // points per trail (the segment from one trail's last point to the next trail is skipped)
uniform float uWNight, uWDay;             // core width, px
uniform float uOutline;                   // outline width each side by day, px
out float vA;                             // opacity
out float vDay;                           // 0 night .. 1 day
out float vD;                             // signed distance from the centre line, px
out float vCore;                          // half core width here, px
out float vOut;                           // outline width here, px

vec3 project(vec3 e) {
    float vx = dot(uView[0], e), vy = dot(uView[1], e), vz = dot(uView[2], e);
    float tanA = inversesqrt(uCamDist * uCamDist - 1.0);
    return vec3(uCenter + vec2(vx, vy) / (uCamDist - vz) / tanA * uRadius, vz * uCamDist);
}

void main() {
    vec3 A = project(aA.xyz), B = project(aB.xyz);
    vec2 d = B.xy - A.xy;
    float L = length(d);
    bool dead = (gl_InstanceID % uK) == uK - 1 || L < 0.01;
    vec2 dir = dead ? vec2(1.0, 0.0) : d / L;
    vec2 nrm = vec2(-dir.y, dir.x);
    float t = aCorner.x;
    vec3 e = normalize(mix(aA.xyz, aB.xyz, t));
    float day = smoothstep(-0.10, 0.25, dot(e, uSun));
    float core = mix(uWNight, uWDay, day) * 0.5;
    float outl = uOutline * day;
    float hw = core + outl + 1.0;                      // +1 px: room for the soft (anti-aliased) edge
    vec2 p = mix(A.xy, B.xy, t) + nrm * aCorner.y * hw + dir * (t * 2.0 - 1.0) * 0.35;   // tiny overlap closes joints
    float facing = mix(A.z, B.z, t);
    vA = dead ? 0.0 : mix(aA.w, aB.w, t) * smoothstep(1.0, 1.35, facing);
    vDay = day; vD = aCorner.y * hw; vCore = core; vOut = outl;
    gl_Position = dead ? vec4(2.0, 2.0, 2.0, 1.0) : vec4(p / uRes * 2.0 - 1.0, 0.0, 1.0);
}
