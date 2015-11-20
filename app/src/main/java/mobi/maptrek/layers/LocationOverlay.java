/*
 * Copyright 2013 Ahmad Saleem
 * Copyright 2013 Hannes Janetzek
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
 */

package mobi.maptrek.layers;

import android.os.SystemClock;
import android.util.Log;

import org.oscim.backend.GL;
import org.oscim.core.Box;
import org.oscim.core.MercatorProjection;
import org.oscim.core.Point;
import org.oscim.core.Tile;
import org.oscim.layers.Layer;
import org.oscim.map.Map;
import org.oscim.renderer.GLShader;
import org.oscim.renderer.GLState;
import org.oscim.renderer.GLViewport;
import org.oscim.renderer.LayerRenderer;
import org.oscim.renderer.MapRenderer;
import org.oscim.utils.FastMath;
import org.oscim.utils.math.Interpolation;

import static org.oscim.backend.GLAdapter.gl;

public class LocationOverlay extends Layer {
	//private final int SHOW_ACCURACY_ZOOM = 18;

	private final Point mLocation = new Point();
    private float mBearing;
	private double mRadius;

    public LocationOverlay(Map map) {
		super(map);
		mRenderer = new LocationIndicator();
        setEnabled(false);
	}

	public void setPosition(double latitude, double longitude, float bearing, float accuracy) {
		mLocation.x = MercatorProjection.longitudeToX(longitude);
		mLocation.y = MercatorProjection.latitudeToY(latitude);
        mBearing = bearing;
		mRadius = accuracy / MercatorProjection.groundResolution(latitude, 1);
		((LocationIndicator) mRenderer).animate(true);
	}

    @Override
	public void setEnabled(boolean enabled) {
		if (enabled == isEnabled())
			return;

		super.setEnabled(enabled);

		if (!enabled)
			((LocationIndicator) mRenderer).animate(false);
	}

    public void setPinned(boolean pinned) {
        Log.e("LO", "setPinned(" + pinned + ")");
        ((LocationIndicator) mRenderer).setPinned(pinned);
    }

	public class LocationIndicator extends LayerRenderer {
		private int mShaderProgram;
		private int hVertexPosition;
		private int hMatrixPosition;
		private int hScale;
		private int hPhase;
		private int hDirection;

		private final float CIRCLE_SIZE = 60;

		private final static long ANIM_RATE = 50;
		private final static long INTERVAL = 8000;

		private final Point mIndicatorPosition = new Point();

		private final Point mScreenPoint = new Point();
		private final Box mBBox = new Box();

		private boolean mInitialized;

		private boolean mLocationIsVisible;

		private boolean mRunAnim;
		private long mAnimStart;

        private boolean mPinned = false;

		public LocationIndicator() {
			super();
		}

        private void setPinned(boolean pinned) {
            mLocationIsVisible = true;
            mPinned = pinned;
        }

		private void animate(boolean enable) {
			if (mRunAnim == enable)
				return;

			mRunAnim = enable;
			if (!enable)
				return;

			final Runnable action = new Runnable() {
				private long lastRun;

				@Override
				public void run() {
					if (!mRunAnim)
						return;

					long diff = SystemClock.elapsedRealtime() - lastRun;
					mMap.postDelayed(this, Math.min(ANIM_RATE, diff));
					mMap.render();
				}
			};

			mAnimStart = SystemClock.elapsedRealtime();
			mMap.postDelayed(action, ANIM_RATE);
		}

		private float animPhase() {
			return (float) ((MapRenderer.frametime - mAnimStart) % INTERVAL) / INTERVAL;
		}

		@Override
		public void update(GLViewport v) {
			if (!mInitialized) {
				init();
				mInitialized = true;
			}

			if (!isEnabled()) {
				setReady(false);
				return;
			}

			if (!v.changed() && !isReady())
				return;

			setReady(true);

            if (mPinned)
            {
                mIndicatorPosition.x = v.pos.x;
                mIndicatorPosition.y = v.pos.y;
                return;
            }

			int width = mMap.getWidth();
			int height = mMap.getHeight();

			// clamp location to a position that can be
			// safely translated to screen coordinates
			v.getBBox(mBBox, 0);

			double x = mLocation.x;
			double y = mLocation.y;

			if (!mBBox.contains(mLocation)) {
				x = FastMath.clamp(x, mBBox.xmin, mBBox.xmax);
				y = FastMath.clamp(y, mBBox.ymin, mBBox.ymax);
			}

			// get position of Location in pixel relative to
			// screen center
			v.toScreenPoint(x, y, mScreenPoint);

			x = mScreenPoint.x;// + width / 2;
			y = mScreenPoint.y;// + height / 2;
            //Log.e("LO", x + ":" + y);

			// clip position to screen boundaries
			int visible = 0;
            double w2 = width / 2;
            double h2 = height / 2;

			if (x > w2 - 5)
				x = w2;
			else if (x < -w2 + 5)
				x = -w2;
			else
				visible++;

			if (y > h2 - 5)
				y = h2;
			else if (y < -h2 + 5)
				y = -h2;
			else
				visible++;

			mLocationIsVisible = (visible == 2);

			// set location indicator position
			v.fromScreenPoint(x, y, mIndicatorPosition);

            //FIXME Workaround for broken getDepth()
            if (v.pos.tilt > 0) {
                mIndicatorPosition.x = mLocation.x;
                mIndicatorPosition.y = mLocation.y;
            }
		}

		@Override
		public void render(GLViewport v) {

			GLState.useProgram(mShaderProgram);
			GLState.blend(true);
			GLState.test(false, false);

			GLState.enableVertexArrays(hVertexPosition, -1);
			MapRenderer.bindQuadVertexVBO(hVertexPosition);

			float radius = CIRCLE_SIZE;

			animate(true);
			boolean viewShed = false;
			if (!mLocationIsVisible /* || pos.zoomLevel < SHOW_ACCURACY_ZOOM */) {
				//animate(true);
			} else {
				float r = (float) (mRadius * v.pos.scale);
                if (r > radius) // || v.pos.zoomLevel >= SHOW_ACCURACY_ZOOM)
                    radius = r;
				viewShed = true;
				//animate(false);
			}
			gl.uniform1f(hScale, radius);

            double x = mIndicatorPosition.x - v.pos.x;
            double y = mIndicatorPosition.y - v.pos.y;
			double tileScale = Tile.SIZE * v.pos.scale;

			v.mvp.setTransScale((float) (x * tileScale), (float) (y * tileScale), 1);
			v.mvp.multiplyMM(v.viewproj, v.mvp);
			v.mvp.setAsUniform(hMatrixPosition);

			if (!viewShed) {
				float phase = Math.abs(animPhase() - 0.5f) * 2;
				//phase = Interpolation.fade.apply(phase);
				phase = Interpolation.swing.apply(phase);

                gl.uniform1f(hPhase, 0.8f + phase * 0.2f);
			} else {
                gl.uniform1f(hPhase, 1);
			}

			if (viewShed && mLocationIsVisible) {
				float rotation = mBearing - 90;
                if (rotation > 180)
                    rotation -= 360;
                else if (rotation < -180)
                    rotation += 360;

				gl.uniform2f(hDirection,
                        (float) Math.cos(Math.toRadians(rotation)),
                        (float) Math.sin(Math.toRadians(rotation)));
			} else {
                gl.uniform2f(hDirection, 0, 0);
			}

			gl.drawArrays(GL.TRIANGLE_STRIP, 0, 4);
		}

		private boolean init() {
			int shader = GLShader.createProgram(vShaderStr, fShaderStr);
			if (shader == 0)
				return false;

			mShaderProgram = shader;
			hVertexPosition = gl.getAttribLocation(shader, "a_pos");
			hMatrixPosition = gl.getUniformLocation(shader, "u_mvp");
			hPhase = gl.getUniformLocation(shader, "u_phase");
			hScale = gl.getUniformLocation(shader, "u_scale");
			hDirection = gl.getUniformLocation(shader, "u_dir");

			return true;
		}

		private final static String vShaderStr = ""
		        + "precision mediump float;"
		        + "uniform mat4 u_mvp;"
		        + "uniform float u_phase;"
		        + "uniform float u_scale;"
		        + "attribute vec2 a_pos;"
		        + "varying vec2 v_tex;"
		        + "void main() {"
		        + "  gl_Position = u_mvp * vec4(a_pos * u_scale * u_phase, 0.0, 1.0);"
		        + "  v_tex = a_pos;"
		        + "}";

		private final static String fShaderStr = ""
		        + "precision mediump float;"
		        + "varying vec2 v_tex;"
		        + "uniform float u_scale;"
		        + "uniform float u_phase;"
		        + "uniform vec2 u_dir;"

		        + "void main() {"
		        + "  float len = 1.0 - length(v_tex);"
		        + "  if (u_dir.x == 0.0 && u_dir.y == 0.0){"
		        + "  gl_FragColor = vec4(1.0, 0.34, 0.13, 1.0) * len;"
		        + "  } else {"
		        ///  outer ring
		        + "  float a = smoothstep(0.0, 2.0 / u_scale, len);"
		        ///  inner ring
		        + "  float b = 0.5 * smoothstep(4.0 / u_scale, 5.0 / u_scale, len);"
		        ///  center point
		        + "  float c = 0.5 * (1.0 - smoothstep(14.0 / u_scale, 16.0 / u_scale, 1.0 - len));"
		        + "  vec2 dir = normalize(v_tex);"
		        + "  float d = 1.0 - dot(dir, u_dir); "
		        ///  0.5 width of viewshed
		        + "  d = clamp(step(0.5, d), 0.4, 0.7);"
		        ///  - subtract inner from outer to create the outline
		        ///  - multiply by viewshed
		        ///  - add center point
		        + "  a = d * (a - (b + c)) + c;"
		        ///+ "  gl_FragColor = vec4(0.2, 0.2, 0.8, 1.0) * a;"
                + "  gl_FragColor = vec4(1.0, 0.34, 0.13, 1.0) * a;"
		        + "}}";

		//private final static String fShaderStr = ""
		//		+ "precision mediump float;"
		//		+ "varying vec2 v_tex;"
		//		+ "uniform float u_scale;"
		//		+ "uniform float u_phase;"
		//		+ "uniform vec2 u_dir;"
		//		+ "void main() {"
		//		+ "  float len = 1.0 - length(v_tex);"
		//		///  outer ring
		//		+ "  float a = smoothstep(0.0, 2.0 / u_scale, len);"
		//		///  inner ring
		//		+ "  float b = 0.8 * smoothstep(3.0 / u_scale, 4.0 / u_scale, len);"
		//		///  center point
		//		+ "  float c = 0.5 * (1.0 - smoothstep(14.0 / u_scale, 16.0 / u_scale, 1.0 - len));"
		//		+ "  vec2 dir = normalize(v_tex);"
		//		+ "  float d = dot(dir, u_dir); "
		//		///  0.5 width of viewshed
		//		+ "  d = clamp(smoothstep(0.7, 0.7 + 2.0/u_scale, d) * len, 0.0, 1.0);"
		//		///  - subtract inner from outer to create the outline
		//		///  - multiply by viewshed
		//		///  - add center point
		//		+ "  a = max(d, (a - (b + c)) + c);"
		//		+ "  gl_FragColor = vec4(0.2, 0.2, 0.8, 1.0) * a;"
		//		+ "}";

	}
}