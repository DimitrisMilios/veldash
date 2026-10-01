"""
Pre-renders the batmobile model into the sprite frames the app draws (assets/car/).

The app never runs a 3D engine: it picks the pre-rendered frame that matches the camera's tilt
and the car's heading relative to the camera, so a real 3D look costs one small bitmap.

Frames (square, transparent, orthographic so the car keeps one scale in every frame):
  car_p{PP}_y000.webp   tilt PP = 0, 5, ... 55 degrees, car seen from straight behind.
                        Drives the 2D <-> 3D switch: the car truly tilts with the map.
  car_p55_y{YYY}.webp   full chase tilt, camera orbiting YYY = 0..180 degrees to the car's
                        right. The app mirrors them for the left side.
Tilt 0 is straight down (top-down 2D); the app rotates that one frame for any heading.

Lighting is fixed to the car's heading (not the camera), so highlights move naturally as the
view tilts. A shadow catcher bakes a soft contact shadow into the alpha.

Usage (Blender 4.2+ / 5.x, runs headless):
  blender -b -P tools/render-car.py -- --glb ~/veldash-data/models/batmobile_jet_car_1989.glb \
      --out app/src/main/assets/car [--size 400] [--samples 64] [--only p55_y000]
"""
import argparse
import math
import os
import sys

import bpy
from mathutils import Euler, Matrix, Vector

TILTS = list(range(0, 56, 5))       # 0..55, matches MapSetup.MAX_PITCH
YAWS = list(range(0, 181, 15))      # 0..180 at full tilt
FULL_TILT = 55
CAR_LENGTH = 4.6                    # model is normalised to this length (scene units)
FRAME_MARGIN = 1.12                 # ortho scale = length * margin: the car never clips


def args():
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    p = argparse.ArgumentParser()
    p.add_argument("--glb", required=True)
    p.add_argument("--out", required=True)
    p.add_argument("--size", type=int, default=400)
    p.add_argument("--samples", type=int, default=64)
    p.add_argument("--only", default=None, help="render just one frame name, e.g. p55_y000")
    p.add_argument("--preview", default=None, help="also write PNGs composited over the map colour here")
    return p.parse_args(argv)


def world_bbox(objs):
    lo = Vector((math.inf,) * 3)
    hi = Vector((-math.inf,) * 3)
    for o in objs:
        for c in o.bound_box:
            w = o.matrix_world @ Vector(c)
            lo = Vector(map(min, lo, w))
            hi = Vector(map(max, hi, w))
    return lo, hi


def center_of(objs):
    lo, hi = world_bbox(objs)
    return (lo + hi) / 2


def import_car(path):
    bpy.ops.import_scene.gltf(filepath=path)
    meshes = [o for o in bpy.context.scene.objects if o.type == "MESH"]
    roots = [o for o in bpy.context.scene.objects if o.parent is None]

    root = bpy.data.objects.new("CarRoot", None)
    bpy.context.scene.collection.objects.link(root)
    for o in roots:
        o.parent = root
    bpy.context.view_layer.update()

    # Forward = from the rear lights to the front lights (falls back to the long axis, +Y).
    front = [o for o in meshes if "frontlight" in o.name.lower()]
    rear = [o for o in meshes if "rearlight" in o.name.lower()]
    if front and rear:
        fwd = center_of(front) - center_of(rear)
    else:
        lo, hi = world_bbox(meshes)
        size = hi - lo
        fwd = Vector((1, 0, 0)) if size.x > size.y else Vector((0, 1, 0))
        print("WARNING: no light meshes found; guessing forward", tuple(fwd))
    fwd.z = 0
    theta = math.atan2(-fwd.x, fwd.y)          # angle of fwd, CCW from +Y
    root.rotation_euler = Euler((0, 0, -theta))
    bpy.context.view_layer.update()

    # Scale to CAR_LENGTH, then put the footprint centre at the origin, wheels on z = 0.
    lo, hi = world_bbox(meshes)
    s = CAR_LENGTH / (hi.y - lo.y)
    root.scale = (s, s, s)
    bpy.context.view_layer.update()
    lo, hi = world_bbox(meshes)
    c = (lo + hi) / 2
    root.location -= Vector((c.x, c.y, lo.z))
    bpy.context.view_layer.update()
    lo, hi = world_bbox(meshes)
    print("car bounds", tuple(round(v, 3) for v in lo), tuple(round(v, 3) for v in hi))
    return meshes


def area_light(name, parent, loc, size, energy, color=(1, 1, 1), target=None, shadow=False):
    data = bpy.data.lights.new(name, type="AREA")
    data.size = size
    data.energy = energy
    data.color = color
    # Only the key throws a shadow onto the catcher: several soft shadows add up to a grey cloud.
    data.use_shadow = shadow
    obj = bpy.data.objects.new(name, data)
    bpy.context.scene.collection.objects.link(obj)
    obj.parent = parent
    obj.location = loc
    if target is not None:
        con = obj.constraints.new("TRACK_TO")
        con.target = target
        con.track_axis = "TRACK_NEGATIVE_Z"
        con.up_axis = "UP_Y"
    return obj


def setup_scene(size, samples):
    scene = bpy.context.scene
    scene.render.engine = "CYCLES"
    scene.cycles.device = "CPU"
    scene.cycles.samples = samples
    scene.cycles.use_denoising = True
    scene.render.film_transparent = True
    scene.render.resolution_x = size
    scene.render.resolution_y = size
    scene.render.resolution_percentage = 100
    scene.render.image_settings.file_format = "WEBP"
    scene.render.image_settings.color_mode = "RGBA"
    scene.render.image_settings.quality = 85

    # Cool grey environment: never seen directly (film is transparent), but it gives the black
    # gloss paint a sheen to reflect, so the car reads against the near-black map.
    world = bpy.data.worlds.new("World")
    scene.world = world
    world.use_nodes = True
    bg = world.node_tree.nodes.get("Background")
    bg.inputs["Color"].default_value = (0.2, 0.22, 0.27, 1)
    bg.inputs["Strength"].default_value = 0.6

    target = bpy.data.objects.new("Target", None)
    scene.collection.objects.link(target)
    target.location = (0, 0, 0.5)

    # Studio lights in the car's frame: a big soft key overhead (roof and bonnet highlights),
    # two cool rims ahead-left/right that outline the fins seen from behind, a weak fill from
    # the chase side, and a low bat-signal yellow kicker behind for the brand accent.
    rig = bpy.data.objects.new("LightRig", None)
    scene.collection.objects.link(rig)
    area_light("Key", rig, (0, -1.0, 7.5), 3.0, 800, target=target, shadow=True)
    area_light("RimL", rig, (-4.5, 4.5, 2.6), 2.5, 900, (0.85, 0.9, 1.0), target)
    area_light("RimR", rig, (4.5, 4.5, 2.6), 2.5, 900, (0.85, 0.9, 1.0), target)
    area_light("Fill", rig, (0, -7.0, 2.0), 4.0, 120, target=target)
    area_light("Kicker", rig, (0, -4.0, 0.5), 1.5, 160, (1.0, 0.85, 0.1), target)

    # Ground: catches the car's contact shadow into the alpha, invisible otherwise.
    bpy.ops.mesh.primitive_plane_add(size=40, location=(0, 0, 0))
    ground = bpy.context.active_object
    ground.name = "ShadowCatcher"
    ground.is_shadow_catcher = True

    cam_data = bpy.data.cameras.new("Cam")
    cam_data.type = "ORTHO"
    cam_data.ortho_scale = CAR_LENGTH * FRAME_MARGIN
    cam_data.clip_end = 200
    cam = bpy.data.objects.new("Cam", cam_data)
    scene.collection.objects.link(cam)
    scene.camera = cam
    return cam


def place_camera(cam, tilt_deg, yaw_deg, dist=30.0):
    """Tilt 0 looks straight down (car nose up); tilt t leans back behind the car; yaw orbits
    the camera to the car's right. The footprint centre always projects to the frame centre."""
    # XYZ order = Rz(yaw) * Rx(tilt): lean back first, then orbit around the vertical.
    rot = Euler((math.radians(tilt_deg), 0, math.radians(yaw_deg)), "XYZ")
    m = rot.to_matrix()
    cam.rotation_euler = rot
    cam.location = m @ Vector((0, 0, dist))


def main():
    a = args()
    bpy.ops.wm.read_factory_settings(use_empty=True)
    import_car(os.path.expanduser(a.glb))
    cam = setup_scene(a.size, a.samples)
    out = os.path.abspath(os.path.expanduser(a.out))
    os.makedirs(out, exist_ok=True)

    frames = [(t, 0) for t in TILTS] + [(FULL_TILT, y) for y in YAWS if y != 0]
    for tilt, yaw in frames:
        name = "p%02d_y%03d" % (tilt, yaw)
        if a.only and a.only != name:
            continue
        place_camera(cam, tilt, yaw)
        bpy.context.scene.render.filepath = os.path.join(out, "car_" + name + ".webp")
        bpy.ops.render.render(write_still=True)
        if a.preview:
            write_preview(bpy.context.scene.render.filepath, os.path.join(a.preview, name + ".png"))
        print("rendered", name, flush=True)


def write_preview(src, dst):
    """The frame over the map background colour (BatStyle C_BG), for judging contrast."""
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    img = bpy.data.images.load(src)
    px = list(img.pixels)
    bg = (0.0033, 0.0037, 0.0052)  # #0B0C10 in linear
    for i in range(0, len(px), 4):
        a = px[i + 3]
        for k in range(3):
            px[i + k] = px[i + k] * a + bg[k] * (1 - a)
        px[i + 3] = 1.0
    img.pixels[:] = px
    img.filepath_raw = dst
    img.file_format = "PNG"
    img.save()
    bpy.data.images.remove(img)


main()
