import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import processing.core.*;

public final class VirtualWorld extends PApplet
{
    public static final int TIMER_ACTION_PERIOD = 100;

    public static final int VIEW_WIDTH = 640;
    public static final int VIEW_HEIGHT = 480;
    public static final int TILE_WIDTH = 32;
    public static final int TILE_HEIGHT = 32;
    public static final int WORLD_WIDTH_SCALE = 2;
    public static final int WORLD_HEIGHT_SCALE = 2;

    public static final int VIEW_COLS = VIEW_WIDTH / TILE_WIDTH;
    public static final int VIEW_ROWS = VIEW_HEIGHT / TILE_HEIGHT;
    public static final int WORLD_COLS = VIEW_COLS * WORLD_WIDTH_SCALE;
    public static final int WORLD_ROWS = VIEW_ROWS * WORLD_HEIGHT_SCALE;

    public static final String IMAGE_LIST_FILE_NAME = "imagelist";
    public static final String DEFAULT_IMAGE_NAME = "background_default";
    public static final int DEFAULT_IMAGE_COLOR = 0x808080;

    public static final String HIVE_IMAGE_NAME = "hive";

    public static String LOAD_FILE_NAME = "world.sav";

    public static final String FAST_FLAG = "-fast";
    public static final String FASTER_FLAG = "-faster";
    public static final String FASTEST_FLAG = "-fastest";
    public static final double FAST_SCALE = 0.5;
    public static final double FASTER_SCALE = 0.25;
    public static final double FASTEST_SCALE = 0.10;

    public static double timeScale = 1.0;

    private ImageStore imageStore;
    private WorldModel world;
    private WorldView view;
    private EventScheduler scheduler;

    private long nextTime;

    private int clicks;
    private TeleporterExit teleportExit;
    public static final int CLICK_RANGE = 1;
    public static final int TRANSFORM_RANGE = 3;

    public void settings() {
        size(VIEW_WIDTH, VIEW_HEIGHT);
    }

    /*
       Processing entry point for "sketch" setup
    */
    public void setup() {
        this.imageStore = new ImageStore(
                createImageColored(TILE_WIDTH, TILE_HEIGHT,
                                   DEFAULT_IMAGE_COLOR));
        this.world = new WorldModel(WORLD_ROWS, WORLD_COLS,
                                    createDefaultBackground(imageStore));
        this.view = new WorldView(VIEW_ROWS, VIEW_COLS, this, world, TILE_WIDTH,
                                  TILE_HEIGHT);
        this.scheduler = new EventScheduler(timeScale);

        loadImages(IMAGE_LIST_FILE_NAME, imageStore, this);
        loadWorld(world, LOAD_FILE_NAME, imageStore);

        scheduleActions(world, scheduler, imageStore);

        nextTime = System.currentTimeMillis() + TIMER_ACTION_PERIOD;
        this.clicks = 0;
        this.teleportExit = null;
    }

    public void draw() {
        long time = System.currentTimeMillis();
        if (time >= nextTime) {
            this.scheduler.updateOnTime(time);
            nextTime = time + TIMER_ACTION_PERIOD;
        }

        view.drawViewport();
    }

    // Just for debugging and for P5
    public void mousePressed() {
        Point pressed = mouseToPoint(mouseX, mouseY);
        System.out.println("CLICK! " + pressed.getX() + ", " + pressed.getY());

        Optional<Entity> entityOptional = world.getOccupant(pressed);
        if (entityOptional.isPresent())
        {
            Entity entity = entityOptional.get();
            System.out.println(entity.getId() + ": " + entity.getClass()); //+ " : " + entity.getHealth()
        }

        /* New code for p5:
        * find all entities within the click (maybe use stream)
        * alternate between:
        * 1. putting down an exit and changing background tiles
        * 2. putting down an entrance and changing background tiles
        *   linking  the entrance to the exit.*/

        //put down exit
        if (clicks % 2 == 0)
        {
            if (world.isOccupied(pressed))
            {
                Entity entity = world.getOccupancyCell(pressed);
                world.removeEntity(entity);
                scheduler.unscheduleAllEvents(entity);
            }
            TeleporterExit tele = Factory.createExit("exit_", pressed, imageStore.getImageList("exit"));
            world.addEntity(tele);

            teleportExit = tele;
        }
        else //put down entrance
        {
            if (world.isOccupied(pressed))
            {
                Entity entity = world.getOccupancyCell(pressed);
                world.removeEntity(entity);
                scheduler.unscheduleAllEvents(entity);
            }
            TeleporterEntrance tele = Factory.createEntrance("entrance_", pressed, imageStore.getImageList("entrance"), Functions.FAIRY_ANIMATION_PERIOD, Functions.FAIRY_ACTION_PERIOD, teleportExit);
            world.addEntity(tele);
            tele.scheduleActions(scheduler, world, imageStore);
        }

        //update clicks counter and linker and link teles if needed
        clicks ++;

        //find all points within range
        BiFunction<Point, Integer, Stream<Point>> pointStreamFunction = (point, range) ->
        {
            List<Point> possible = new ArrayList<Point>();
            for (int i = -range; i <= range; i++)
            {
                for (int j = -range; j <= range; j ++)
                {
                    possible.add(new Point(point.x + i, point.y + j));
                }
            }
            return possible.stream().filter(p->world.withinBounds(p));
        };
        Stream<Point> withinRange = pointStreamFunction.apply(pressed, CLICK_RANGE);

        //change all background tiles in range
        withinRange.forEach(p -> world.setBackground(p, new Background(HIVE_IMAGE_NAME, imageStore.getImageList(HIVE_IMAGE_NAME))));

        //use the points to find all entities within range that are changeable and call the change function
        pointStreamFunction.apply(pressed, TRANSFORM_RANGE).filter(p->world.isOccupied(p)).map(p->world.getOccupancyCell(p)).forEach(entity->{
            //System.out.println(entity.getId());
            if (entity instanceof Changeable)
            {
                ((Changeable)entity).change(world, scheduler, imageStore);
            }
        });
    }

    private Point mouseToPoint(int x, int y)
    {
        return view.getViewport().viewportToWorld(mouseX/TILE_WIDTH, mouseY/TILE_HEIGHT);
    }
    public void keyPressed() {
        if (key == CODED) {
            int dx = 0;
            int dy = 0;

            switch (keyCode) {
                case UP:
                    dy = -1;
                    break;
                case DOWN:
                    dy = 1;
                    break;
                case LEFT:
                    dx = -1;
                    break;
                case RIGHT:
                    dx = 1;
                    break;
            }
            view.shiftView(dx, dy);
        }
    }

    public static Background createDefaultBackground(ImageStore imageStore) {
        return new Background(DEFAULT_IMAGE_NAME,
                                    imageStore.getImageList(DEFAULT_IMAGE_NAME));
    }

    public static PImage createImageColored(int width, int height, int color) {
        PImage img = new PImage(width, height, RGB);
        img.loadPixels();
        for (int i = 0; i < img.pixels.length; i++) {
            img.pixels[i] = color;
        }
        img.updatePixels();
        return img;
    }

    static void loadImages(
            String filename, ImageStore imageStore, PApplet screen)
    {
        try {
            Scanner in = new Scanner(new File(filename));
            imageStore.loadImages(in, screen);
        }
        catch (FileNotFoundException e) {
            System.err.println(e.getMessage());
        }
    }

    public static void loadWorld(
            WorldModel world, String filename, ImageStore imageStore)
    {
        try {
            Scanner in = new Scanner(new File(filename));
            world.load(in, imageStore);
        }
        catch (FileNotFoundException e) {
            System.err.println(e.getMessage());
        }
    }

    public static void scheduleActions(
            WorldModel world, EventScheduler scheduler, ImageStore imageStore)
    {
        for (Entity entity : world.getEntities()) {
            if (entity instanceof AnimatingEntity) {
                ((AnimatingEntity) entity).scheduleActions(scheduler, world, imageStore);
            }
        }
    }

    public static void parseCommandLine(String[] args) {
        if (args.length > 1)
        {
            if (args[0].equals("file"))
            {

            }
        }
        for (String arg : args) {
            switch (arg) {
                case FAST_FLAG:
                    timeScale = Math.min(FAST_SCALE, timeScale);
                    break;
                case FASTER_FLAG:
                    timeScale = Math.min(FASTER_SCALE, timeScale);
                    break;
                case FASTEST_FLAG:
                    timeScale = Math.min(FASTEST_SCALE, timeScale);
                    break;
            }
        }
    }

    public static void main(String[] args) {
        parseCommandLine(args);
        PApplet.main(VirtualWorld.class);
    }
}
