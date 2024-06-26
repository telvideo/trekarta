/*
 * Copyright 2024 Andrey Novikov
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 *
 */

package mobi.maptrek.location;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.preference.PreferenceManager;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.oscim.core.BoundingBox;
import org.oscim.core.GeoPoint;
import org.oscim.utils.math.MathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import mobi.maptrek.Configuration;
import mobi.maptrek.MainActivity;
import mobi.maptrek.MapTrek;
import mobi.maptrek.R;
import mobi.maptrek.data.MapObject;
import mobi.maptrek.data.Route;
import mobi.maptrek.data.source.FileDataSource;
import mobi.maptrek.io.Manager;
import mobi.maptrek.io.RouteManager;
import mobi.maptrek.util.Geo;
import mobi.maptrek.util.StringFormatter;

public class NavigationService extends BaseNavigationService implements OnSharedPreferenceChangeListener {
    private static final Logger logger = LoggerFactory.getLogger(NavigationService.class);

    private static final int NOTIFICATION_ID = 25502;

    private static final int DEFAULT_POINT_PROXIMITY = 3;
    private static final int DEFAULT_ROUTE_PROXIMITY = 20; // TODO: implement dynamic proximity
    private static final String CURRENT_ROUTE_FILE_NAME = "CurrentRoute" + RouteManager.EXTENSION;

    private ILocationService mLocationService = null;
    private Location mLastKnownLocation;

    private boolean mUseTraverse = true;

    /**
     * Active route point
     */
    public MapObject navPoint = null;
    /**
     * Previous route point
     */
    public MapObject prevPoint = null;
    /**
     * Active route
     */
    public Route navRoute = null;

    public int navDirection = 0;
    /**
     * Active route point index
     */
    public int navCurrentRoutePoint = -1;
    private double navRouteDistance = -1;

    public int navProximity = 0;
    /**
     * Distance to active point
     */
    public double navDistance = 0d;
    public double navBearing = 0d;
    public long navTurn = 0;
    public double navVMG = 0d;
    public int navETE = Integer.MAX_VALUE;
    public double navCourse = 0d;
    public double navXTK = Double.NEGATIVE_INFINITY;

    private int navSecs = 0;
    private int prevSecs = 0;
    // 10 min, 6 min, 3 min average
    private final double[] avgVMG = new double[] {0.0, 0.0, 0.0};

    private String ntTitle = null;
    private String ntBearing = null;
    private String ntDistance = null;

    private final Binder mBinder = new LocalBinder();

    private static final String PREF_NAVIGATION_PROXIMITY = "navigation_proximity";
    private static final String PREF_NAVIGATION_TRAVERSE = "navigation_traverse";

    @Override
    public void onCreate() {
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        onSharedPreferenceChanged(sharedPreferences, PREF_NAVIGATION_PROXIMITY);
        onSharedPreferenceChanged(sharedPreferences, PREF_NAVIGATION_TRAVERSE);
        sharedPreferences.registerOnSharedPreferenceChangeListener(this);

        logger.debug("Service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null)
            return START_NOT_STICKY;

        String action = intent.getAction();
        Bundle extras = intent.getExtras();
        logger.debug("Command: {}", action);
        if (action.equals(NAVIGATE_TO_POINT)) {
            if (extras == null)
                return START_NOT_STICKY;
            MapObject mo = new MapObject(extras.getDouble(EXTRA_LATITUDE), extras.getDouble(EXTRA_LONGITUDE));
            mo.name = extras.getString(EXTRA_NAME);
            mo.proximity = extras.getInt(EXTRA_PROXIMITY);
            navigateTo(mo);
        }
        if (action.equals(NAVIGATE_TO_OBJECT)) {
            if (extras == null)
                return START_NOT_STICKY;
            long id = extras.getLong(EXTRA_ID);
            MapObject mo = MapTrek.getMapObject(id);
            if (mo == null)
                return START_NOT_STICKY;
            navigateTo(mo);
        }
        if (action.equals(NAVIGATE_VIA_ROUTE)) {
            if (extras == null)
                return START_NOT_STICKY;
            Route route = extras.getParcelable(EXTRA_ROUTE);
            int dir = extras.getInt(EXTRA_ROUTE_DIRECTION, DIRECTION_FORWARD);
            int start = extras.getInt(EXTRA_ROUTE_START, -1);
            navigateTo(route, dir);
            if (start != -1)
                setRoutePoint(start);
        }
        if (action.equals(RESUME_NAVIGATION)) {
            if (navPoint != null) // we are already navigating
                return START_NOT_STICKY;
            navCurrentRoutePoint = Configuration.getNavigationRoutePoint();
            navDirection = Configuration.getNavigationRouteDirection();
            navPoint = Configuration.getNavigationPoint();
            if (navPoint == null) { // we do not know what to resume
                logger.error("No destination to resume");
                return START_NOT_STICKY;
            }
            if (Configuration.getNavigationViaRoute()) {
                loadRoute();
                if (navRoute != null) {
                    resumeRoute();
                } else {
                    logger.error("No route to resume");
                    stopNavigation(true);
                }
            } else {
                resumePoint();
            }
        }
        if (action.equals(STOP_NAVIGATION) || action.equals(PAUSE_NAVIGATION)) {
            if (action.equals(STOP_NAVIGATION))
                stopNavigation(false);
            else
                updateNavigationState(STATE_PAUSED);
            navPoint = null;
            stopForeground(true);
            disconnect();
            stopSelf();
        }
        updateNotification();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        PreferenceManager.getDefaultSharedPreferences(this).unregisterOnSharedPreferenceChangeListener(this);
        logger.warn("Service stopped");
    }

    public class LocalBinder extends Binder implements INavigationService {
        @Override
        public boolean isNavigating() {
            return navPoint != null;
        }

        @Override
        public boolean isNavigatingViaRoute() {
            return navRoute != null;
        }

        @Override
        public GeoPoint getCurrentPoint() {
            return navPoint.coordinates;
        }

        @Override
        public List<GeoPoint> getRemainingPoints() {
            List<GeoPoint> points = new ArrayList<>(1);
            if (isNavigatingViaRoute()) {
                int first = navCurrentRoutePoint;
                int last = navDirection == DIRECTION_REVERSE ? -1 : navRoute.size();
                for (int i = first; i != last; i += navDirection)
                    points.add(navRoute.get(i));
            } else {
                points.add(navPoint.coordinates);
            }
            return points;
        }

        @Override
        public boolean hasNextRoutePoint() {
            return NavigationService.this.hasNextRoutePoint();
        }

        @Override
        public boolean hasPrevRoutePoint() {
            return NavigationService.this.hasPrevRoutePoint();
        }

        @Override
        public void nextRoutePoint() {
            if (!hasNextRoutePoint())
                return;

            NavigationService.this.nextRoutePoint();
        }

        @Override
        public void prevRoutePoint() {
            if (!hasPrevRoutePoint())
                return;

            NavigationService.this.prevRoutePoint();
        }

        @Override
        public GeoPoint getPrevRoutePoint() {
            if (prevPoint != null)
               return prevPoint.coordinates;
            else
                return null;
        }

        @Override
        public BoundingBox getRouteBoundingBox() {
            BoundingBox box;
            if (isNavigatingViaRoute()) {
                box = navRoute.getBoundingBox();
            } else {
                box = new BoundingBox();
                box.extend(navPoint.coordinates.getLatitude(), navPoint.coordinates.getLongitude());
            }
            if (mLastKnownLocation != null)
                box.extend(mLastKnownLocation.getLatitude(), mLastKnownLocation.getLongitude());
            return box;
        }

        @Override
        public String getInstructionText() {
            if (isNavigatingViaRoute()) {
                return navRoute.get(navRouteCurrentIndex()).getText();
            } else {
                return navPoint.name;
            }
        }

        @Override
        public int getSign() {
            //noinspection StatementWithEmptyBody
            if (isNavigatingViaRoute()) {
                return navRoute.get(navRouteCurrentIndex()).getSign();
            } else {
                // TODO: add sign for point navigation?
            }
            return -1;
        }

        @Override
        public float getDistance() {
            if (isNavigatingViaRoute())
                return (float) (navRouteDistanceLeft() + navDistance);
            else
                return (float) navDistance;
        }

        @Override
        public float getBearing() {
            return (float) navBearing;
        }

        @Override
        public float getTurn() {
            return navTurn;
        }

        @Override
        public float getVmg() {
            return (float) navVMG;
        }

        @Override
        public float getXtk() {
            return (float) navXTK;
        }

        @Override
        public int getEte() {
            if (isNavigatingViaRoute() && navRouteCurrentIndex() < navRoute.size() - 1)
                return navRouteETE(navRouteDistanceLeft() + navDistance);
            else
                return navETE;
        }

        @Override
        public float getPointDistance() {
            return (float) navDistance;
        }

        @Override
        public int getPointEte() {
            return navETE;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (PREF_NAVIGATION_TRAVERSE.equals(key)) {
            mUseTraverse = true; //sharedPreferences.getBoolean(key, getResources().getBoolean(R.bool.def_navigation_traverse));
        }
    }

    private void connect() {
        if (!EventBus.getDefault().isRegistered(this))
            EventBus.getDefault().register(this);
        startService(new Intent(getApplicationContext(), LocationService.class).setAction(BaseLocationService.ENABLE_BACKGROUND_LOCATIONS));
        if (mLocationService == null)
            bindService(new Intent(this, LocationService.class), locationConnection, BIND_AUTO_CREATE);
    }

    private void disconnect() {
        EventBus.getDefault().unregister(this);
        startService(new Intent(getApplicationContext(), LocationService.class).setAction(BaseLocationService.DISABLE_BACKGROUND_LOCATIONS));
        if (mLocationService != null) {
            mLocationService.unregisterLocationCallback(locationListener);
            unbindService(locationConnection);
            mLocationService = null;
        }
        mLastKnownLocation = null;
    }

    private Notification getNotification(boolean force) {
        String name = navPoint.name != null ? navPoint.name : getString(R.string.msgNavigatingPoint);
        String title = getString(R.string.msgNavigating, name);
        String bearing = StringFormatter.angleH(navBearing);
        String distance = StringFormatter.distanceH(navDistance);

        if (!force && title.equals(ntTitle) && bearing.equals(ntBearing) && distance.equals(ntDistance))
            return null; // not changed

        ntTitle = title;
        ntBearing = bearing;
        ntDistance = distance;

        StringBuilder sb = new StringBuilder(40);
        sb.append(getString(R.string.msgNavigationProgress, distance, bearing));
        String message = sb.toString();
        sb.append(". ");
        sb.append(getString(R.string.msgNavigationActions));
        sb.append(".");
        String bigText = sb.toString();

        Intent iLaunch = new Intent(Intent.ACTION_MAIN);
        iLaunch.addCategory(Intent.CATEGORY_LAUNCHER);
        iLaunch.setComponent(new ComponentName(getApplicationContext(), MainActivity.class));
        iLaunch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        PendingIntent piResult = PendingIntent.getActivity(this, 0, iLaunch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent iStop = new Intent(STOP_NAVIGATION, null, getApplicationContext(), NavigationService.class);
        PendingIntent piStop = PendingIntent.getService(this, 0, iStop, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Icon stopIcon = Icon.createWithResource(this, R.drawable.ic_cancel);

        Intent iPause = new Intent(PAUSE_NAVIGATION, null, getApplicationContext(), NavigationService.class);
        PendingIntent piPause = PendingIntent.getService(this, 0, iPause, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Icon pauseIcon = Icon.createWithResource(this, R.drawable.ic_pause);

        Notification.Action actionStop = new Notification.Action.Builder(stopIcon, getString(R.string.actionStop), piStop).build();
        Notification.Action actionPause = new Notification.Action.Builder(pauseIcon, getString(R.string.actionPause), piPause).build();

        Notification.Builder builder = new Notification.Builder(this);
        if (Build.VERSION.SDK_INT > 25)
            builder.setChannelId("ongoing");
        builder.setSmallIcon(R.mipmap.ic_stat_navigation);
        builder.setContentIntent(piResult);
        builder.setContentTitle(title);
        builder.setContentText(message);
        builder.setWhen(System.currentTimeMillis());
        builder.setStyle(new Notification.BigTextStyle().setBigContentTitle(title).bigText(bigText));
        builder.addAction(actionPause);
        builder.addAction(actionStop);
        builder.setGroup("maptrek");
        if (Build.VERSION.SDK_INT >= 28)
            builder.setCategory(Notification.CATEGORY_NAVIGATION);
        else
            builder.setCategory(Notification.CATEGORY_PROGRESS);
        builder.setPriority(Notification.PRIORITY_LOW);
        builder.setVisibility(Notification.VISIBILITY_PUBLIC);
        builder.setColor(getResources().getColor(R.color.colorAccent, getTheme()));
        builder.setOngoing(true);
        return builder.build();
    }

    private void updateNotification() {
        if (navPoint != null) {
            Notification notification = getNotification(false);
            if (notification != null) {
                NotificationManager notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                notificationManager.notify(NOTIFICATION_ID, notification);
            }
        }
    }

    public void stopNavigation(boolean disconnect) {
        logger.debug("Stop navigation");
        updateNavigationState(STATE_STOPPED);
        stopForeground(true);
        clearNavigation();
        if (disconnect)
            disconnect();
    }

    private void clearNavigation() {
        navPoint = null;
        prevPoint = null;
        navRoute = null;

        navDirection = 0;
        navCurrentRoutePoint = -1;

        navProximity = DEFAULT_POINT_PROXIMITY;
        navDistance = 0f;
        navBearing = 0f;
        navTurn = 0;
        navVMG = 0f;
        navETE = Integer.MAX_VALUE;
        navCourse = 0f;
        navXTK = Double.NEGATIVE_INFINITY;

        navSecs = 0;
        prevSecs = 0;
        avgVMG[0] = 0.0;
        avgVMG[1] = 0.0;
        avgVMG[2] = 0.0;

        Configuration.setNavigationPoint(null);
        Configuration.setNavigationViaRoute(false);
    }

    private void navigateTo(final MapObject point) {
        if (navPoint != null)
            stopNavigation(false);
        navPoint = point;

        Configuration.setNavigationPoint(navPoint);
        Configuration.setNavigationViaRoute(false);
        resumePoint();
    }

    private void resumePoint() {
        navProximity = navPoint.proximity > 0 ? navPoint.proximity : DEFAULT_POINT_PROXIMITY;
        updateNavigationState(STATE_STARTED);
        if (mLastKnownLocation != null)
            calculateNavigationStatus();

        if (Build.VERSION.SDK_INT < 34)
            startForeground(NOTIFICATION_ID, getNotification(true));
        else
            //noinspection DataFlowIssue - can not be null because of force = true
            startForeground(NOTIFICATION_ID, getNotification(true), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        connect();
    }

    private void navigateTo(final Route route, final int direction) {
        if (navPoint != null)
            stopNavigation(false);
        navRoute = route;
        navDirection = direction;
        navCurrentRoutePoint = navDirection == 1 ? 1 : navRoute.size() - 2;

        Configuration.setNavigationViaRoute(true);
        Configuration.setNavigationRouteDirection(navDirection);
        Configuration.setNavigationRoutePoint(navCurrentRoutePoint);
        saveRoute();
        resumeRoute();
    }

    private void resumeRoute() {
        navPoint = new MapObject(navRoute.get(navCurrentRoutePoint));
        prevPoint = new MapObject(navRoute.get(navCurrentRoutePoint - navDirection));
        navProximity = DEFAULT_ROUTE_PROXIMITY;
        navRouteDistance = -1;
        navCourse = prevPoint.coordinates.bearingTo(navPoint.coordinates);
        Configuration.setNavigationPoint(navPoint);
        updateNavigationState(STATE_STARTED);
        if (mLastKnownLocation != null)
            calculateNavigationStatus();

        if (Build.VERSION.SDK_INT < 34)
            startForeground(NOTIFICATION_ID, getNotification(true));
        else
            //noinspection DataFlowIssue - can not be null because of force = true
            startForeground(NOTIFICATION_ID, getNotification(true), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        connect();
    }

    public void setRoutePoint(int point) {
        navCurrentRoutePoint = point;
        navPoint = new MapObject(navRoute.get(navCurrentRoutePoint));
        Configuration.setNavigationRoutePoint(navCurrentRoutePoint);
        Configuration.setNavigationPoint(navPoint);
        int prev = navCurrentRoutePoint - navDirection;
        if (prev >= 0 && prev < navRoute.size())
            prevPoint = new MapObject(navRoute.get(prev));
        else
            prevPoint = null;
        navRouteDistance = -1;
        navCourse = prevPoint == null ? 0d : prevPoint.coordinates.bearingTo(navPoint.coordinates);
        navETE = Integer.MAX_VALUE;
        calculateNavigationStatus();
        updateNavigationState(STATE_NEXT_ROUTE_POINT);
    }

    public MapObject getNextRoutePoint() {
        int next = navCurrentRoutePoint + navDirection;
        if (next >= 0 && next < navRoute.size())
            return new MapObject(navRoute.get(next));
        return null;
    }

    public void nextRoutePoint() throws IndexOutOfBoundsException {
        navCurrentRoutePoint += navDirection;
        navPoint = new MapObject(navRoute.get(navCurrentRoutePoint));
        prevPoint = new MapObject(navRoute.get(navCurrentRoutePoint - navDirection));
        navRouteDistance = -1;
        navCourse = prevPoint.coordinates.bearingTo(navPoint.coordinates);
        if (avgVMG[0] < 0) avgVMG[0] = 0.0;
        if (avgVMG[1] < 0) avgVMG[1] = 0.0;
        if (avgVMG[2] < 0) avgVMG[2] = 0.0;
        navETE = Integer.MAX_VALUE;
        Configuration.setNavigationRoutePoint(navCurrentRoutePoint);
        Configuration.setNavigationPoint(navPoint);
        calculateNavigationStatus();
        updateNavigationState(STATE_NEXT_ROUTE_POINT);
    }

    public void prevRoutePoint() throws IndexOutOfBoundsException {
        navCurrentRoutePoint -= navDirection;
        navPoint = new MapObject(navRoute.get(navCurrentRoutePoint));
        int prev = navCurrentRoutePoint - navDirection;
        if (prev >= 0 && prev < navRoute.size())
            prevPoint = new MapObject(navRoute.get(prev));
        else
            prevPoint = null;
        navRouteDistance = -1;
        navCourse = prevPoint == null ? 0d : prevPoint.coordinates.bearingTo(navPoint.coordinates);
        if (avgVMG[0] < 0) avgVMG[0] = 0.0;
        if (avgVMG[1] < 0) avgVMG[1] = 0.0;
        if (avgVMG[2] < 0) avgVMG[2] = 0.0;
        navETE = Integer.MAX_VALUE;
        Configuration.setNavigationRoutePoint(navCurrentRoutePoint);
        Configuration.setNavigationPoint(navPoint);
        calculateNavigationStatus();
        updateNavigationState(STATE_NEXT_ROUTE_POINT);
    }

    public boolean hasNextRoutePoint() {
        if (navRoute == null)
            return false;
        boolean hasNext = false;
        if (navDirection == DIRECTION_FORWARD)
            hasNext = (navCurrentRoutePoint + navDirection) < navRoute.size();
        if (navDirection == DIRECTION_REVERSE)
            hasNext = (navCurrentRoutePoint + navDirection) >= 0;
        return hasNext;
    }

    public boolean hasPrevRoutePoint() {
        if (navRoute == null)
            return false;
        boolean hasPrev = false;
        if (navDirection == DIRECTION_FORWARD)
            hasPrev = (navCurrentRoutePoint - navDirection) >= 0;
        if (navDirection == DIRECTION_REVERSE)
            hasPrev = (navCurrentRoutePoint - navDirection) < navRoute.size();
        return hasPrev;
    }

    public int navRouteCurrentIndex() {
        return navDirection == DIRECTION_FORWARD ? navCurrentRoutePoint : navRoute.size() - navCurrentRoutePoint - 1;
    }

    /**
     * Calculates distance between current route point and last route point.
     *
     * @return distance left
     */
    public double navRouteDistanceLeft() {
        if (navRouteDistance < 0) {
            navRouteDistance = navRouteDistanceLeftTo(navRoute.size() - 1);
        }
        return navRouteDistance;
    }

    /**
     * Calculates distance between current route point and route point with specified index.
     * Method honors navigation direction.
     *
     * @param index point index
     * @return distance left
     */
    public double navRouteDistanceLeftTo(int index) {
        int current = navRouteCurrentIndex();
        int progress = index - current;

        if (progress <= 0)
            return 0.0;

        double distance = 0.0;
        if (navDirection == DIRECTION_FORWARD)
            distance = navRoute.distanceBetween(navCurrentRoutePoint, index);
        if (navDirection == DIRECTION_REVERSE)
            distance = navRoute.distanceBetween(navRoute.size() - index - 1, navCurrentRoutePoint);

        return distance;
    }

    /**
     * Calculates ETE for route segment.
     *
     * @param index point index
     * @return segment ETE
     */
    public int navRoutePointETE(int index) {
        if (index == 0)
            return 0;
        int ete = Integer.MAX_VALUE;
        if (avgVMG[0] > 0) {
            int i = navDirection == DIRECTION_FORWARD ? index : navRoute.size() - index - 1;
            int j = i - navDirection;
            double distance = navRoute.get(i).vincentyDistance(navRoute.get(j));
            ete = (int) Math.round(distance / avgVMG[0] / 60);
        }
        return ete;
    }

    /**
     * Calculates route ETE.
     *
     * @param distance route distance
     * @return route ETE
     */
    public int navRouteETE(double distance) {
        int eta = Integer.MAX_VALUE;
        if (avgVMG[0] > 0) {
            eta = (int) Math.round(distance / avgVMG[0] / 60);
        }
        return eta;
    }

    public int navRouteETETo(int index) {
        double distance = navRouteDistanceLeftTo(index);
        if (distance <= 0.0)
            return 0;

        return navRouteETE(distance);
    }

    private void calculateNavigationStatus() {
        int secs = (int) (mLastKnownLocation.getElapsedRealtimeNanos() * 1e-9);
        int diff = secs - prevSecs;
        if (diff < 1)
            return;

        //if (diff < 600)
            navSecs += diff;
        prevSecs = secs;

        GeoPoint point = new GeoPoint(mLastKnownLocation.getLatitude(), mLastKnownLocation.getLongitude());
        double distance = point.vincentyDistance(navPoint.coordinates);
        double bearing = point.bearingTo(navPoint.coordinates);

        // turn
        long turn = Math.round(bearing - mLastKnownLocation.getBearing());
        if (Math.abs(turn) > 180) {
            turn = turn - (long) (Math.signum(turn)) * 360;
        }

        // vmg
        double vmg = Geo.vmg(mLastKnownLocation.getSpeed(), Math.abs(turn));
        avgVMG[0] = movingAverage(vmg, avgVMG[0], MathUtils.clamp(600 - diff, 1, navSecs)); // 10 minutes average
        avgVMG[1] = movingAverage(vmg, avgVMG[1], MathUtils.clamp(360 - diff, 1, navSecs)); // 6 minutes average
        avgVMG[2] = movingAverage(vmg, avgVMG[2], MathUtils.clamp(180 - diff, 1, navSecs)); // 3 minutes average

        // ete
        int ete = Integer.MAX_VALUE;
        if (navETE <= 3 && avgVMG[2] > 0 && avgVMG[2] > avgVMG[1]) // otherwise ete can jump back and forth
            ete = (int) Math.round(distance / avgVMG[2] / 60);
        else if (navETE <= 6 && avgVMG[1] > 0 && avgVMG[1] > avgVMG[0])
            ete = (int) Math.round(distance / avgVMG[1] / 60);
        else if (avgVMG[0] > 0)
            ete = (int) Math.round(distance / avgVMG[0] / 60);

        double xtk = Double.NEGATIVE_INFINITY;

        boolean hasNext = hasNextRoutePoint();
        if (distance < navProximity) {
            if (hasNext) {
                nextRoutePoint();
            } else {
                updateNavigationState(STATE_REACHED);
                stopNavigation(true);
            }
            return;
        }

        if (prevPoint != null) {
            double dtk = prevPoint.coordinates.bearingTo(navPoint.coordinates);
            xtk = Geo.xtk(distance, dtk, bearing);

            if (xtk == Double.NEGATIVE_INFINITY) {
                if (mUseTraverse && hasNext) {
                    MapObject nextPoint = getNextRoutePoint();
                    if (nextPoint != null) {
                        double dtk2 = nextPoint.coordinates.bearingTo(navPoint.coordinates);
                        double xtk2 = Geo.xtk(0, dtk2, bearing);
                        if (xtk2 != Double.NEGATIVE_INFINITY) {
                            nextRoutePoint();
                            return;
                        }
                    }
                }
            }
        }

        if (distance != navDistance || bearing != navBearing || turn != navTurn || vmg != navVMG || ete != navETE || xtk != navXTK) {
            navDistance = distance;
            navBearing = bearing;
            navTurn = turn;
            navVMG = vmg;
            navETE = ete;
            navXTK = xtk;
            updateNavigationStatus();
        }
    }

    private void updateNavigationState(final int state) {
        if (state != STATE_STOPPED && state != STATE_REACHED)
            updateNotification();
        sendBroadcast(new Intent(BROADCAST_NAVIGATION_STATE).putExtra(EXTRA_STATE, state).setPackage(getPackageName()));
        logger.trace("State dispatched");
    }

    private void updateNavigationStatus() {
        updateNotification();
        sendBroadcast(new Intent(BROADCAST_NAVIGATION_STATUS).putExtra(EXTRA_MOVING_TARGET, navPoint.moving).setPackage(getPackageName()));
        logger.trace("Status dispatched");
    }

    /** @noinspection unused*/
    @Subscribe
    public void onMapObjectUpdated(MapObject.UpdatedEvent event) {
        logger.debug("onMapObjectUpdated({})", (event.mapObject.equals(navPoint)));
        if (event.mapObject.equals(navPoint))
            calculateNavigationStatus();
    }

    private void saveRoute() {
        File cacheDir = getCacheDir();
        FileDataSource source = new FileDataSource();
        source.name = "CurrentRoute";
        File file = new File(cacheDir, CURRENT_ROUTE_FILE_NAME);
        source.path = file.getAbsolutePath();
        source.routes.add(navRoute);
        Manager.save(source, new Manager.OnSaveListener() {
            @Override
            public void onSaved(FileDataSource source) {
                // TODO: what to do?
            }

            @Override
            public void onError(FileDataSource source, Exception e) {
                // TODO: what to do?
            }
        });
    }

    private void loadRoute() {
        File cacheDir = getCacheDir();
        File file = new File(cacheDir, CURRENT_ROUTE_FILE_NAME);
        Manager manager = Manager.getDataManager(file.getName());
        if (manager != null) {
            try {
                FileDataSource source = manager.loadData(new FileInputStream(file), file.getAbsolutePath());
                source.path = file.getAbsolutePath();
                source.setLoaded();
                navRoute = source.routes.get(0);
            } catch (Exception e) {
                logger.error("Saved route not found");
            }
        }
    }

    private final ServiceConnection locationConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder service) {
            mLocationService = (ILocationService) service;
            mLocationService.registerLocationCallback(locationListener);
            logger.debug("Location service connected");
        }

        public void onServiceDisconnected(ComponentName className) {
            mLocationService = null;
            logger.debug("Location service disconnected");
        }
    };

    private final ILocationListener locationListener = new ILocationListener() {
        @Override
        public void onLocationChanged() {
            if (mLocationService == null)
                return;
            Location location = mLocationService.getLocation();
            if ((SystemClock.elapsedRealtimeNanos() - location.getElapsedRealtimeNanos()) > 1e+9 * 30) // do not trust location which is more then 30 seconds old
                return;
            mLastKnownLocation = location;
            if (prevSecs == 0)
                prevSecs = (int) (mLastKnownLocation.getElapsedRealtimeNanos() * 1e-9);

            if (navPoint != null) {
                if (prevPoint == null) // set to current location to correctly calculate XTK
                    prevPoint = new MapObject(mLastKnownLocation.getLatitude(), mLastKnownLocation.getLongitude());
                calculateNavigationStatus();
            }
        }

        @Override
        public void onGpsStatusChanged() {
        }
    };

    private double movingAverage(double current, double previous, int ratio) {
        // return (1.0 - ratio) * previous + ratio * current;
        // https://stackoverflow.com/a/50854247
        return previous + (current - previous) / ratio;
    }
}
