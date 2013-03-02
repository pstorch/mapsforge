/*
 * Copyright 2010, 2011, 2012 mapsforge.org
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
package org.mapsforge.map.layer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.mapsforge.core.graphics.Bitmap;
import org.mapsforge.core.graphics.Canvas;
import org.mapsforge.core.graphics.Color;
import org.mapsforge.core.graphics.GraphicFactory;
import org.mapsforge.core.model.BoundingBox;
import org.mapsforge.core.model.LatLong;
import org.mapsforge.core.model.MapPosition;
import org.mapsforge.core.model.Point;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.PausableThread;
import org.mapsforge.map.model.MapViewPosition;
import org.mapsforge.map.view.FrameBuffer;
import org.mapsforge.map.view.MapView;

public class LayerManager extends PausableThread {
	private static final Logger LOGGER = Logger.getLogger(LayerManager.class.getName());
	private static final int MILLISECONDS_PER_FRAME = 50;

	private static BoundingBox getBoundingBox(MapPosition mapPosition, Canvas canvas) {
		double pixelX = MercatorProjection.longitudeToPixelX(mapPosition.latLong.longitude, mapPosition.zoomLevel);
		double pixelY = MercatorProjection.latitudeToPixelY(mapPosition.latLong.latitude, mapPosition.zoomLevel);

		int halfCanvasWidth = canvas.getWidth() / 2;
		int halfCanvasHeight = canvas.getHeight() / 2;
		long mapSize = MercatorProjection.getMapSize(mapPosition.zoomLevel);

		double pixelXMin = Math.max(0, pixelX - halfCanvasWidth);
		double pixelYMin = Math.max(0, pixelY - halfCanvasHeight);
		double pixelXMax = Math.min(mapSize, pixelX + halfCanvasWidth);
		double pixelYMax = Math.min(mapSize, pixelY + halfCanvasHeight);

		double minLatitude = MercatorProjection.pixelYToLatitude(pixelYMax, mapPosition.zoomLevel);
		double minLongitude = MercatorProjection.pixelXToLongitude(pixelXMin, mapPosition.zoomLevel);
		double maxLatitude = MercatorProjection.pixelYToLatitude(pixelYMin, mapPosition.zoomLevel);
		double maxLongitude = MercatorProjection.pixelXToLongitude(pixelXMax, mapPosition.zoomLevel);

		return new BoundingBox(minLatitude, minLongitude, maxLatitude, maxLongitude);
	}

	private static Point getCanvasPosition(MapPosition mapPosition, Canvas canvas) {
		LatLong centerPoint = mapPosition.latLong;
		byte zoomLevel = mapPosition.zoomLevel;

		int halfCanvasWidth = canvas.getWidth() / 2;
		int halfCanvasHeight = canvas.getHeight() / 2;

		double pixelX = MercatorProjection.longitudeToPixelX(centerPoint.longitude, zoomLevel) - halfCanvasWidth;
		double pixelY = MercatorProjection.latitudeToPixelY(centerPoint.latitude, zoomLevel) - halfCanvasHeight;
		return new Point(pixelX, pixelY);
	}

	private final int backgroundColor;
	private final Canvas drawingCanvas;
	private final List<Layer> layers;
	private final MapView mapView;
	private final MapViewPosition mapViewPosition;
	private boolean redrawNeeded;

	public LayerManager(MapView mapView, MapViewPosition mapViewPosition, GraphicFactory graphicFactory) {
		super();

		this.mapView = mapView;
		this.mapViewPosition = mapViewPosition;

		this.drawingCanvas = graphicFactory.createCanvas();
		this.layers = new CopyOnWriteArrayList<Layer>();
		this.backgroundColor = graphicFactory.createColor(Color.WHITE);
	}

	public List<Layer> getLayers() {
		return this.layers;
	}

	/**
	 * Requests an asynchronous redrawing of all layers.
	 */
	public void redrawLayers() {
		this.redrawNeeded = true;
		synchronized (this) {
			notify();
		}
	}

	@Override
	protected void afterRun() {
		for (Layer layer : this.getLayers()) {
			layer.destroy();
		}
	}

	@Override
	protected void doWork() throws InterruptedException {
		long startTime = System.nanoTime();
		this.redrawNeeded = false;

		FrameBuffer frameBuffer = this.mapView.getFrameBuffer();
		Bitmap bitmap = frameBuffer.getDrawingBitmap();
		if (bitmap != null) {
			bitmap.fillColor(this.backgroundColor);
			this.drawingCanvas.setBitmap(bitmap);

			MapPosition mapPosition = this.mapViewPosition.getMapPosition();
			BoundingBox boundingBox = getBoundingBox(mapPosition, this.drawingCanvas);
			Point canvasPosition = getCanvasPosition(mapPosition, this.drawingCanvas);

			for (Layer layer : this.getLayers()) {
				if (layer.isVisible()) {
					layer.draw(boundingBox, mapPosition.zoomLevel, this.drawingCanvas, canvasPosition);
				}
			}

			frameBuffer.frameFinished(mapPosition);
			this.mapView.repaint();
		}

		long elapsedMilliseconds = (System.nanoTime() - startTime) / 1000000;
		long timeSleep = MILLISECONDS_PER_FRAME - elapsedMilliseconds;

		if (timeSleep > 1 && !isInterrupted()) {
			if (LOGGER.isLoggable(Level.FINE)) {
				LOGGER.log(Level.FINE, "sleeping (ms): " + timeSleep);
			}
			sleep(timeSleep);
		}
	}

	@Override
	protected ThreadPriority getThreadPriority() {
		return ThreadPriority.NORMAL;
	}

	@Override
	protected boolean hasWork() {
		return this.redrawNeeded;
	}
}
