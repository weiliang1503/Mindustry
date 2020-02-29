package mindustry.ui.dialogs;

import arc.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.graphics.g3d.*;
import arc.graphics.gl.*;
import arc.input.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.event.*;
import arc.scene.ui.layout.*;
import arc.util.*;
import arc.util.ArcAnnotate.*;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.graphics.g3d.*;
import mindustry.graphics.g3d.PlanetGrid.*;
import mindustry.type.*;
import mindustry.ui.*;

import static mindustry.Vars.*;

public class PlanetDialog extends FloatingDialog{
    private static final Color
        outlineColor = Pal.accent.cpy().a(1f),
        hoverColor = Pal.accent.cpy().a(0.5f),
        borderColor = Pal.accent.cpy().a(0.3f),
        shadowColor = new Color(0, 0, 0, 0.7f);
    private static final float camLength = 4f;
    float outlineRad = 1.15f;

    //the base planet that's being rendered
    private final Planet solarSystem = Planets.sun;

    private final Mesh[] outlines = new Mesh[10];
    private final Camera3D cam = new Camera3D();
    private final VertexBatch3D batch = new VertexBatch3D(false, true, 0);
    private final PlaneBatch3D projector = new PlaneBatch3D();
    private final Mat3D mat = new Mat3D();
    private final Vec3 camRelative = new Vec3();

    private final Bloom bloom = new Bloom(Core.graphics.getWidth()/4, Core.graphics.getHeight()/4, true, false, true){{
        setClearColor(0, 0, 0, 0);
        blurPasses = 6;
    }};

    private Planet planet = Planets.starter;
    private float lastX, lastY;
    private @Nullable Sector selected, hovered;
    private Table stable, infoTable;

    public PlanetDialog(){
        super("", Styles.fullDialog);

        addCloseButton();
        buttons.addImageTextButton("$techtree", Icon.tree, () -> ui.tech.show()).size(230f, 64f);

        camRelative.set(0, 0f, camLength);
        projector.setScaling(1f / 300f);

        update(() -> {
            Vec3 v = Tmp.v33.set(Core.input.mouseX(), Core.input.mouseY(), 0);

            if(planet.isLandable()){
                hovered = planet.getSector(cam.getMouseRay(), outlineRad);

                if(Core.input.keyDown(KeyCode.MOUSE_LEFT)){
                    float upV = camRelative.angle(Vec3.Y);
                    float xscale = 9f, yscale = 10f;
                    float margin = 1;

                    //scale X speed depending on polar coordinate
                    float speed = 1f - Math.abs(upV - 90) / 90f;

                    camRelative.rotate(cam.up, (v.x - lastX) / xscale * speed);

                    //prevent user from scrolling all the way up and glitching it out
                    float amount = (v.y - lastY) / yscale;
                    amount = Mathf.clamp(upV + amount, margin, 180f - margin) - upV;

                    camRelative.rotate(Tmp.v31.set(cam.up).rotate(cam.direction, 90), amount);
                }

            }else{
                hovered = selected = null;
            }

            lastX = v.x;
            lastY = v.y;
        });

        addListener(new ElementGestureListener(){
            @Override
            public void tap(InputEvent event, float x, float y, int count, KeyCode button){
                selected = hovered;
                if(selected != null){
                    updateSelected();
                }
            }
        });

        infoTable = new Table();

        stable = new Table(t -> {
            t.background(Styles.black3);
            t.margin(12f);
            t.add("this is some arbitrary text.");
        });

        stable.act(1f);
        stable.pack();
        stable.setPosition(0, 0, Align.center);

        shown(this::setup);
    }

    void setup(){
        cont.clear();
        titleTable.remove();

        cont.addRect((x, y, w, h) -> render()).grow();
    }

    private void render(){
        Draw.flush();
        Gl.clear(Gl.depthBufferBit);
        Gl.enable(Gl.depthTest);

        Gl.enable(Gl.cullFace);
        Gl.cullFace(Gl.back);

        //lock to up vector so it doesn't get confusing
        cam.up.set(Vec3.Y);

        cam.resize(Core.graphics.getWidth(), Core.graphics.getHeight());
        cam.position.set(planet.position).add(camRelative);
        cam.lookAt(planet.position);
        cam.update();

        projector.proj(cam.combined());
        batch.proj(cam.combined());

        bloom.capture();

        renderPlanet(solarSystem);
        if(planet.isLandable()){
            //TODO
            renderSectors(planet);
        }

        if(false)
        Draw.batch(projector, () -> {
            if(selected != null){
                setPlane(selected);
                stable.draw();
            }
        });

        bloom.render();

        Gl.disable(Gl.cullFace);
        //Gl.disable(Gl.depthTest);

        if(false && selected != null){
            Vec3 pos = cam.project(Tmp.v31.set(selected.tile.v).setLength(outlineRad));
            stable.setPosition(pos.x, pos.y, Align.center);
            stable.draw();
        }

        cam.update();
    }

    private void renderPlanet(Planet planet){
        //render planet at offsetted position in the world

        if(false){
            bloom.capture();
        }

        planet.mesh.render(cam.combined(), planet.getTransform(mat));

        if(false){
            bloom.render();

            Gl.enable(Gl.depthTest);
            Gl.enable(Gl.blend);
            Gl.depthMask(true);
        }

        renderOrbit(planet);

        for(Planet child : planet.children){
            renderPlanet(child);
        }
    }

    private void renderOrbit(Planet planet){
        if(planet.parent == null) return;

        Vec3 center = planet.parent.position;
        float radius = planet.orbitRadius;
        int points = (int)(radius * 50);
        Angles.circleVectors(points, radius, (cx, cy) -> batch.vertex(Tmp.v32.set(center).add(cx, 0, cy), Pal.gray));
        batch.flush(Gl.lineLoop);
    }

    private void renderSectors(Planet planet){
        //apply transformed position
        batch.proj().mul(planet.getTransform(mat));

        for(Sector sec : planet.sectors){
            if(sec.save == null){
                draw(sec, shadowColor, -0.001f);
            }
        }

        if(hovered != null){
            draw(hovered, hoverColor, -0.001f);
            drawBorders(hovered, borderColor);
        }

        if(selected != null){
            drawSelection(selected);
            drawBorders(selected, borderColor);

            //TODO use better input
            if(Core.input.keyTap(KeyCode.SPACE)){
                control.playSector(selected);
                ui.planet.hide();
            }
        }

        batch.flush(Gl.triangles);

        //render sector grid
        Mesh mesh = outline(planet.grid.size);
        Shader shader = Shaders.planetGrid;
        Vec3 tile = planet.intersect(cam.getMouseRay(), outlineRad);
        //Log.info(tile);
        Shaders.planetGrid.mouse.lerp(tile == null ? Vec3.Zero : tile.sub(planet.position).rotate(Vec3.Y, planet.getRotation()), 0.2f);

        shader.begin();
        shader.setUniformMatrix4("u_proj", cam.combined().val);
        shader.setUniformMatrix4("u_trans", planet.getTransform(mat).val);
        shader.apply();
        mesh.render(shader, Gl.lines);
        shader.end();
    }

    private void drawBorders(Sector sector, Color base){
        Color color = Tmp.c1.set(base).a(base.a + 0.3f + Mathf.absin(Time.globalTime(), 5f, 0.3f));

        float r1 = 1f;
        float r2 = outlineRad + 0.001f;

        for(int i = 0; i < sector.tile.corners.length; i++){
            Corner c = sector.tile.corners[i], next = sector.tile.corners[(i+1) % sector.tile.corners.length];

            Tmp.v31.set(c.v).setLength(r2);
            Tmp.v32.set(next.v).setLength(r2);
            Tmp.v33.set(c.v).setLength(r1);

            batch.tri2(Tmp.v31, Tmp.v32, Tmp.v33, color);

            Tmp.v31.set(next.v).setLength(r2);
            Tmp.v32.set(next.v).setLength(r1);
            Tmp.v33.set(c.v).setLength(r1);

            batch.tri2(Tmp.v31, Tmp.v32, Tmp.v33, color);
        }

        if(batch.getNumVertices() >= batch.getMaxVertices() - 6 * 6){
            batch.flush(Gl.triangles);
        }
    }

    private void updateSelected(){
        stable.clear();
        stable.background(Styles.black6);

        //TODO add strings to bundle after prototyping is done

        stable.add("[accent]" + selected.id).row();
        stable.addImage().color(Pal.accent).fillX().height(3f).pad(3f).row();
        stable.add(selected.save != null ? selected.save.getPlayTime() : "[lightgray]Unexplored").row();

        stable.add("Resources:").row();
        stable.table(t -> {
            t.left();
            int idx = 0;
            int max = 5;
            for(UnlockableContent c : selected.data.resources){
                t.addImage(c.icon(Cicon.small)).padRight(3);
                if(++idx % max == 0) t.row();
            }

            for(int i = 0; i < Math.min(selected.data.floorCounts.length, 3); i++){
                t.addImage(selected.data.floors[i].icon(Cicon.small)).padRight(3);
                if(++idx % max == 0) t.row();
            }
        }).fillX().row();

        stable.pack();
        stable.setPosition(0, 0, Align.center);
    }

    private void setPlane(Sector sector){
        float rotation = planet.getRotation();

        projector.setPlane(
            //origin on sector position
            Tmp.v33.set(sector.tile.v).setLength(outlineRad + 0.1f),
            //face up
            sector.plane.project(Tmp.v32.set(sector.tile.v).add(Vec3.Y)).sub(sector.tile.v).nor(),
            //right vector
            Tmp.v31.set(Tmp.v32).add(sector.tile.v).rotate(sector.tile.v, 90).sub(sector.tile.v).nor()
        );
    }

    private void draw(Sector sector, Color color, float offset){
        float rr = outlineRad + offset;
        for(int i = 0; i < sector.tile.corners.length; i++){
            Corner c = sector.tile.corners[i], next = sector.tile.corners[(i+1) % sector.tile.corners.length];
            batch.tri(Tmp.v31.set(c.v).setLength(rr), Tmp.v32.set(next.v).setLength(rr), Tmp.v33.set(sector.tile.v).setLength(rr), color);
        }
    }

    private void drawSelection(Sector sector){
        float length = 0.1f;
        float arad = outlineRad + 0.0001f;

        for(int i = 0; i < sector.tile.corners.length; i++){
            Corner next = sector.tile.corners[(i + 1) % sector.tile.corners.length];
            Corner curr = sector.tile.corners[i];
            sector.tile.v.scl(arad);
            Tmp.v31.set(curr.v).sub(sector.tile.v).setLength(length).add(sector.tile.v);
            Tmp.v32.set(next.v).sub(sector.tile.v).setLength(length).add(sector.tile.v);
            sector.tile.v.scl(1f / arad);

            batch.tri(curr.v, next.v, Tmp.v31, Pal.accent);
            batch.tri(Tmp.v31, next.v, Tmp.v32, Pal.accent);
        }
    }

    private Mesh outline(int size){
        if(outlines[size] == null){
            outlines[size] = MeshBuilder.buildHex(new HexMesher(){
                @Override
                public float getHeight(Vec3 position){
                    return 0;
                }

                @Override
                public Color getColor(Vec3 position){
                    return outlineColor;
                }
            }, size, true, outlineRad, 0.2f);
        }
        return outlines[size];
    }
}
