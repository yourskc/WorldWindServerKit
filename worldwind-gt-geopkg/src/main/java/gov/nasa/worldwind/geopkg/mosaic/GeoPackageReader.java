/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2002-2010, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package gov.nasa.worldwind.geopkg.mosaic;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import gov.nasa.worldwind.geopkg.GeoPackage;
import gov.nasa.worldwind.geopkg.Tile;
import gov.nasa.worldwind.geopkg.TileEntry;
import gov.nasa.worldwind.geopkg.TileMatrix;
import gov.nasa.worldwind.geopkg.TileReader;

import org.geotools.coverage.CoverageFactoryFinder;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.GridEnvelope2D;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.coverage.grid.io.AbstractGridCoverage2DReader;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.factory.Hints;
import org.geotools.geometry.GeneralEnvelope;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.geotools.util.Utilities;
import org.geotools.util.logging.Logging;
import org.opengis.coverage.grid.Format;
import org.opengis.coverage.grid.GridEnvelope;
import org.opengis.parameter.GeneralParameterValue;
import org.opengis.parameter.ParameterValue;
import org.opengis.referencing.ReferenceIdentifier;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

/**
 * GeoPackage reader for OGC GeoPackage Tile Encoding.
 *
 * @author Justin Deoliveira
 * @author Niels Charlier
 * @author Bruce Schubert
 */
public class GeoPackageReader extends AbstractGridCoverage2DReader {

    /**
     * The {@link Logger} for this {@link GeoPackageReader}.
     */
    private final static Logger LOGGER = Logging.getLogger("gov.nasa.worldwind.geopkg.mosaic");

    protected final static int DEFAULT_TILE_SIZE = 256;

    protected final static int ZOOM_LEVEL_BASE = 2;

    protected GridCoverageFactory coverageFactory;

    protected File sourceFile;

    protected Map<String, TileEntry> tiles = new HashMap<String, TileEntry>();

    public GeoPackageReader(Object source, Hints hints) throws IOException {
        coverageFactory = CoverageFactoryFinder.getGridCoverageFactory(this.hints);
        sourceFile = GeoPackageFormat.getFileFromSource(source);
        GeoPackage file = new GeoPackage(sourceFile);

        try {
            // Set the coverage name to the name of the the first raster tileset.
            coverageName = null;   // default value was "geotools_coverage"
            for (TileEntry tile : file.tiles()) {
                tiles.put(tile.getTableName(), tile);
                if (coverageName == null) {
                    coverageName = tile.getTableName();
                }
            }
        } finally {
            file.close();
        }
    }

    @Override
    public Format getFormat() {
        return new GeoPackageFormat();
    }

    @Override
    protected boolean checkName(String coverageName) {
        Utilities.ensureNonNull("coverageName", coverageName);
        return tiles.keySet().contains(coverageName);
    }

    @Override
    public GeneralEnvelope getOriginalEnvelope(String coverageName) {
        if (!checkName(coverageName)) {
            throw new IllegalArgumentException(
                    "The specified coverageName " + coverageName + "is not supported");
        }
        return new GeneralEnvelope(tiles.get(coverageName).getBounds());
    }

    @Override
    protected double[] getHighestRes(String coverageName) {
        if (!checkName(coverageName)) {
            throw new IllegalArgumentException("The specified coverageName " + coverageName
                    + "is not supported");
        }

        List<TileMatrix> matrices = tiles.get(coverageName).getTileMatricies();
        TileMatrix matrix = matrices.get(matrices.size() - 1);
        return new double[]{matrix.getXPixelSize(), matrix.getYPixelSize()};
    }

    @Override
    public GridEnvelope getOriginalGridRange(String coverageName) {
        if (!checkName(coverageName)) {
            throw new IllegalArgumentException("The specified coverageName " + coverageName
                    + "is not supported");
        }

        List<TileMatrix> matrices = tiles.get(coverageName).getTileMatricies();
        TileMatrix matrix = matrices.get(matrices.size() - 1);
        return new GridEnvelope2D(new Rectangle(matrix.getMatrixWidth() * matrix.getTileWidth(), matrix.getMatrixHeight() * matrix.getTileHeight()));
    }

    @Override
    public CoordinateReferenceSystem getCoordinateReferenceSystem(String coverageName) {
        if (!checkName(coverageName)) {
            throw new IllegalArgumentException("The specified coverageName " + coverageName
                    + "is not supported");
        }

        try {
            return CRS.decode("EPSG:" + tiles.get(coverageName).getSrid(), true);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, e.getMessage(), e);
            return null;
        }
    }

    @Override
    public String[] getGridCoverageNames() {
        return tiles.keySet().toArray(new String[tiles.size()]);
    }

    @Override
    public int getGridCoverageCount() {
        return tiles.size();
    }

    /**
     * Reads from the first raster coverage in the GeoPackage.
     *
     * @param parameters Array containing the requested grid geometry as a
     * {@code Parameter<GridGeometry2D>} descriptor-value pair with a
     * {@code AbstractGridFormat.READ_GRIDGEOMETRY2D} descriptor
     *
     * @return A grid coverage that contains the supplied grid geometry
     *
     * @throws IllegalArgumentException
     * @throws IOException
     */
    @Override
    public GridCoverage2D read(GeneralParameterValue[] parameters) throws IllegalArgumentException, IOException {
        return read(coverageName, parameters);
    }

    /**
     * Reads from the named raster coverage.
     *
     * @param coverageName The name of the coverage to be read
     * @param parameters Array containing the requested grid geometry as a
     * {@code Parameter<GridGeometry2D>} descriptor-value pair with a
     * {@code AbstractGridFormat.READ_GRIDGEOMETRY2D} descriptor
     *
     * @return A grid coverage that contains the supplied grid geometry
     *
     * @throws IllegalArgumentException
     * @throws IOException
     */
    @Override
    public GridCoverage2D read(String coverageName, GeneralParameterValue[] parameters) throws IllegalArgumentException, IOException {
        TileEntry entry = tiles.get(coverageName);
        BufferedImage image = null;
        ReferencedEnvelope resultEnvelope = null;
        GeoPackage file = new GeoPackage(sourceFile);
        try {
            CoordinateReferenceSystem crs = getCoordinateReferenceSystem(coverageName);

            ReferencedEnvelope requestedEnvelope = null;
            Rectangle dim = null;

            // Extract the GridGeometry2D from the parameters and set the
            // requested evelope and dimensions.
            if (parameters != null) {
                for (int i = 0; i < parameters.length; i++) {
                    final ParameterValue param = (ParameterValue) parameters[i];
                    final ReferenceIdentifier name = param.getDescriptor().getName();
                    if (name.equals(AbstractGridFormat.READ_GRIDGEOMETRY2D.getName())) {
                        final GridGeometry2D gg = (GridGeometry2D) param.getValue();
                        try {
                            requestedEnvelope = ReferencedEnvelope.create(gg.getEnvelope(), gg.getCoordinateReferenceSystem()).transform(crs, true);;
                        } catch (Exception e) {
                            requestedEnvelope = null;
                        }
                        dim = gg.getGridRange2D().getBounds();
                        continue;
                    }
                }
            }

            int leftTile, topTile, rightTile, bottomTile;

            //find the closest zoom based on horizontal resolution
            TileMatrix bestMatrix = null;
            if (requestedEnvelope != null && dim != null) {
                //requested res
                double horRes = requestedEnvelope.getSpan(0) / dim.getWidth(); //proportion of total width that is being requested
                double worldSpan = crs.getCoordinateSystem().getAxis(0).getMaximumValue() - crs.getCoordinateSystem().getAxis(0).getMinimumValue();

                //loop over matrices            
                double difference = Double.MAX_VALUE;
                for (TileMatrix matrix : entry.getTileMatricies()) {
                    double newRes = worldSpan / (matrix.getMatrixWidth() * matrix.getTileWidth());
                    double newDifference = Math.abs(horRes - newRes);
                    if (newDifference < difference) {
                        difference = newDifference;
                        bestMatrix = matrix;
                    }
                }
            }
            if (bestMatrix == null) {
                bestMatrix = entry.getTileMatricies().get(0);
            }

            // Get the range of available tiles from database
            leftTile = file.getTileBound(entry, bestMatrix.getZoomLevel(), false, false);   // booleans: isMax, isRow
            rightTile = file.getTileBound(entry, bestMatrix.getZoomLevel(), true, false);
            topTile = file.getTileBound(entry, bestMatrix.getZoomLevel(), false, true);     // min tile_row
            bottomTile = file.getTileBound(entry, bestMatrix.getZoomLevel(), true, true);   // max tile_row

            double resX = (crs.getCoordinateSystem().getAxis(0).getMaximumValue() - crs.getCoordinateSystem().getAxis(0).getMinimumValue()) / bestMatrix.getMatrixWidth();
            double resY = (crs.getCoordinateSystem().getAxis(1).getMaximumValue() - crs.getCoordinateSystem().getAxis(1).getMinimumValue()) / bestMatrix.getMatrixHeight();
            double originX = crs.getCoordinateSystem().getAxis(0).getMinimumValue(); // left
            double originY = crs.getCoordinateSystem().getAxis(1).getMaximumValue(); // top
            int tileWidth = bestMatrix.getTileWidth();
            int tileHeight = bestMatrix.getTileHeight();
            double pixSizeX = bestMatrix.getXPixelSize();
            double pixSizeY = bestMatrix.getYPixelSize();

            if (requestedEnvelope != null) { // crop tile set to requested envelope       

                // Test if the requested envelope aligns with tile matrix boundaries
                double remX = (requestedEnvelope.getMinimum(0) - originX) % resX;
                double remY = (originY - requestedEnvelope.getMaximum(1)) % resY;
                if (dim.getWidth() == tileWidth && dim.getHeight() == tileHeight && remX < pixSizeX && remY < pixSizeY) {
                    leftTile = Math.max(leftTile, (int) Math.round((requestedEnvelope.getMinimum(0) - originX) / resX));
                    topTile = Math.max(topTile, (int) Math.round((originY - requestedEnvelope.getMaximum(1)) / resY));
                    rightTile = leftTile;
                    bottomTile = topTile;
                } else {
                    leftTile = Math.max(leftTile, (int) Math.round(Math.floor((requestedEnvelope.getMinimum(0) - originX) / resX)));
                    rightTile = Math.max(leftTile, (int) Math.min(rightTile, Math.round(Math.floor((requestedEnvelope.getMaximum(0) - originX) / resX))));
                    topTile = Math.max(topTile, (int) Math.round(Math.floor((originY - requestedEnvelope.getMaximum(1)) / resY)));
                    bottomTile = Math.max(topTile, (int) Math.min(bottomTile, Math.round(Math.floor((originY - requestedEnvelope.getMinimum(1)) / resY))));
                }
            }
            boolean isSingleTileRequest = leftTile == rightTile && topTile == bottomTile;

            int width = (int) (rightTile - leftTile + 1) * tileWidth;
            int height = (int) (bottomTile - topTile + 1) * tileHeight;

            //recalculate the envelope we are actually returning
            resultEnvelope = new ReferencedEnvelope(originX + leftTile * resX, originX + (rightTile + 1) * resX, originY - topTile * resY, originY - (bottomTile + 1) * resY, crs);

            TileReader it;
            it = file.reader(entry, bestMatrix.getZoomLevel(), bestMatrix.getZoomLevel(), leftTile, rightTile, topTile, bottomTile);

            while (it.hasNext()) {
                Tile tile = it.next();

                BufferedImage tileImage = readImage(tile.getData());

                ////////////////////////////////////////////////////////////////
                // Uncomment block to draw a border around the tiles for debugging
//                {
//                    Graphics2D graphics = tileImage.createGraphics();
//                    float thickness = 2;
//                    graphics.setStroke(new BasicStroke(thickness));
//                    graphics.drawRect(0, 0, tileImage.getWidth(), tileImage.getHeight());
//                }
                ////////////////////////////////////////////////////////////////
                if (image == null) {
                    if (isSingleTileRequest) {
                        // Copy the source image type
                        image = getStartImage(tileImage, width, height);
                    } else {
                        image = getStartImage(width, height);
                    }
                }

                // Get the tile coordinates within the mosaic
                int posx = (int) (tile.getColumn() - leftTile) * tileWidth;
                int posy = (int) (tile.getRow() - topTile) * tileHeight;

                if (isSingleTileRequest) {
                    // Copy the source image data
                    image.getRaster().setRect(posx, posy, tileImage.getData());
                } else {
                    // Draw the tile in the mosaic. We 'draw' versus 'copy data' to 
                    // accomdate potentially different SampleModels between image tiles,
                    // e.g., when there's a mix of PNG and JPEG image types in the table.
                    Graphics2D g2 = image.createGraphics();
                    g2.drawImage(tileImage, posx, posy, tileWidth, tileHeight, null);
                }
            }

            it.close();

            if (image == null) { // no tiles ??
                image = getStartImage(width, height);
            }
        } finally {
            file.close();
        }
        return coverageFactory.create(entry.getTableName(), image, resultEnvelope);
    }

    protected static BufferedImage readImage(byte[] data) throws IOException {
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        Object source = bis;
        ImageInputStream iis = ImageIO.createImageInputStream(source);
        Iterator<?> readers = ImageIO.getImageReaders(iis);
        ImageReader reader = (ImageReader) readers.next();
        reader.setInput(iis, true);
        ImageReadParam param = reader.getDefaultReadParam();

        return reader.read(0, param);
    }

    protected BufferedImage getStartImage(BufferedImage copyFrom, int width, int height) {
        Map<String, Object> properties = null;

        if (copyFrom.getPropertyNames() != null) {
            properties = new HashMap<String, Object>();
            for (String name : copyFrom.getPropertyNames()) {
                properties.put(name, copyFrom.getProperty(name));
            }
        }

        SampleModel sm = copyFrom.getSampleModel().createCompatibleSampleModel(width, height);
        WritableRaster raster = Raster.createWritableRaster(sm, null);

        BufferedImage image = new BufferedImage(copyFrom.getColorModel(), raster,
                copyFrom.isAlphaPremultiplied(), (Hashtable<?, ?>) properties);

        //white background
//        Graphics2D g2D = (Graphics2D) image.getGraphics();
//        Color save = g2D.getColor();
//        g2D.setColor(Color.WHITE);
//        g2D.fillRect(0, 0, image.getWidth(), image.getHeight());
//        g2D.setColor(save);
        return image;
    }

    protected BufferedImage getStartImage(int imageType, int width, int height) {
        if (imageType == BufferedImage.TYPE_CUSTOM) {
            imageType = BufferedImage.TYPE_3BYTE_BGR;
//            imageType = BufferedImage.TYPE_INT_ARGB;
        }

        BufferedImage image = new BufferedImage(width, height, imageType);

        //white background
//        Graphics2D g2D = (Graphics2D) image.getGraphics();
//        Color save = g2D.getColor();
//        g2D.setColor(Color.WHITE);
//        g2D.fillRect(0, 0, image.getWidth(), image.getHeight());
//        g2D.setColor(save);
        return image;
    }

    protected BufferedImage getStartImage(int width, int height) {
        return getStartImage(BufferedImage.TYPE_CUSTOM, width, height);
    }
}