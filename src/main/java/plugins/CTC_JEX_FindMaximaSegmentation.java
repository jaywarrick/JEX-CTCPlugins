package plugins;

import Database.DBObjects.JEXData;
import Database.DBObjects.JEXEntry;
import Database.DataReader.ImageReader;
import Database.DataReader.RoiReader;
import Database.DataWriter.FileWriter;
import Database.DataWriter.ImageWriter;
import Database.DataWriter.RoiWriter;
import Database.Definition.Parameter;
import Database.Definition.ParameterSet;
import Database.Definition.TypeName;
import Database.SingleUserDatabase.JEXWriter;
import function.JEXCrunchable;
import function.imageUtility.MaximumFinder;
import function.plugin.mechanism.InputMarker;
import function.plugin.mechanism.JEXPlugin;
import function.plugin.mechanism.MarkerConstants;
import function.plugin.mechanism.OutputMarker;
import function.plugin.mechanism.ParameterMarker;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.plugin.filter.RankFilters;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import image.roi.IdPoint;
import image.roi.PointList;
import image.roi.ROIPlus;

import java.awt.Shape;
import java.io.File;
import java.util.HashMap;
import java.util.TreeMap;

import org.scijava.plugin.Plugin;

import jex.statics.JEXStatics;
import logs.Logs;
import miscellaneous.JEXCSVWriter;
import tables.DimTable;
import tables.DimensionMap;
import weka.core.converters.JEXTableWriter;

/**
 * This is a JEXperiment function template To use it follow the following instructions
 * 
 * 1. Fill in all the required methods according to their specific instructions 2. Place the file in the Functions/SingleDataPointFunctions folder 3. Compile and run JEX!
 * 
 * JEX enables the use of several data object types The specific API for these can be found in the main JEXperiment folder. These API provide methods to retrieve data from these objects, create new objects and handle the data they contain.
 * 
 * @author erwinberthier
 * 
 */

@Plugin(
		type = JEXPlugin.class,
		name="CTC - Find Maxima Segmentation",
		menuPath="CTC Toolbox",
		visible=true,
		description="Find maxima in a grayscale image or one color of a multi-color image."
		)
public class CTC_JEX_FindMaximaSegmentation extends JEXPlugin {
	
	public CTC_JEX_FindMaximaSegmentation()
	{}
	
	// ----------------------------------------------------
	// --------- INFORMATION ABOUT THE FUNCTION -----------
	// ----------------------------------------------------
	

	@Override
	public int getMaxThreads()
	{
		return 10;
	}

	
	// ----------------------------------------------------
	// --------- INPUT OUTPUT DEFINITIONS -----------------
	// ----------------------------------------------------
	
	/////////// Define Inputs ///////////
		
	@InputMarker(name="Image", type=MarkerConstants.TYPE_IMAGE, description="Image to be processed.", optional=false)
	JEXData imageData;
	
	@InputMarker(name="ROI (optional)", type=MarkerConstants.TYPE_ROI, description="Roi to be processed.", optional=true)
	JEXData roiData;
	
	/////////// Define Parameters ///////////
	
	@ParameterMarker(uiOrder=1, name="Old Min", description="Image Intensity Value", ui=MarkerConstants.UI_TEXTFIELD, defaultText="0.0")
	double oldMin;
	
	@ParameterMarker(uiOrder=2, name="Old Max", description="Image Intensity Value", ui=MarkerConstants.UI_TEXTFIELD, defaultText="4095.0")
	double oldMax;
	
	@ParameterMarker(uiOrder=3, name="New Min", description="Image Intensity Value", ui=MarkerConstants.UI_TEXTFIELD, defaultText="0.0")
	double newMin;
	
	@ParameterMarker(uiOrder=4, name="New Max", description="Image Intensity Value", ui=MarkerConstants.UI_TEXTFIELD, defaultText="65535.0")
	double newMax;
	
	@ParameterMarker(uiOrder=5, name="Gamma", description="0.1-5.0, value of 1 results in no change", ui=MarkerConstants.UI_TEXTFIELD, defaultText="1.0")
	double gamma;
	
	@ParameterMarker(uiOrder=6, name="Output Bit Depth", description="Depth of the outputted image", ui=MarkerConstants.UI_DROPDOWN, choices={ "8", "16", "32" }, defaultChoice=1)
	int bitDepth;
	
	/////////// Define Outputs ///////////
	
	@OutputMarker(name="Adjusted Image", type=MarkerConstants.TYPE_IMAGE, flavor="", description="The resultant adjusted image", enabled=true)
	JEXData output;

	// ----------------------------------------------------
	// --------- THE ACTUAL MEAT OF THIS FUNCTION ---------
	// ----------------------------------------------------
	
	/**
	 * Perform the algorithm here
	 * 
	 */
	@Override
	public boolean run(JEXEntry optionalEntry)
	{
		try
		{
			/* COLLECT DATA INPUTS */
			
			// if/else to figure out whether or not valid image data has been given;
			// ends run if not
			if(imageData == null || !imageData.getTypeName().getType().equals(JEXData.IMAGE))
			{
				return false;
			}
			
			// Check whether Roi available
			boolean roiProvided = false;
			if(roiData != null && roiData.getTypeName().getType().equals(JEXData.ROI))
			{
				roiProvided = true;
			}
			
			
			
			/* GATHER PARAMETERS */
			double despeckleR = Double.parseDouble(this.parameters.getValueOfParameter("Pre-Despeckle Radius"));
			double smoothR = Double.parseDouble(this.parameters.getValueOfParameter("Pre-Smoothing Radius"));
			String colorDimName = this.parameters.getValueOfParameter("Color Dim Name");
			String nuclearDimValue = this.parameters.getValueOfParameter("Maxima Color Dim Value");
			String segDimValue = this.parameters.getValueOfParameter("Segmentation Color Dim Value");
			double tolerance = Double.parseDouble(this.parameters.getValueOfParameter("Tolerance"));
			double threshold = Double.parseDouble(this.parameters.getValueOfParameter("Threshold"));
			boolean excludePtsOnEdges = Boolean.parseBoolean(this.parameters.getValueOfParameter("Exclude Maximima on Edges?"));
			boolean excludeSegsOnEdges = Boolean.parseBoolean(this.parameters.getValueOfParameter("Exclude Segments on Edges?"));
			boolean isEDM = Boolean.parseBoolean(this.parameters.getValueOfParameter("Is EDM?"));
			boolean lightBackground = !Boolean.parseBoolean(this.parameters.getValueOfParameter("Particles Are White?"));
			boolean maximaOnly = Boolean.parseBoolean(this.parameters.getValueOfParameter("Output Maxima Only?"));
			
			
			
			/* RUN THE FUNCTION */
			// validate roiMap (if provided)
			TreeMap<DimensionMap,ROIPlus> roiMap;
			if(roiProvided)	roiMap = RoiReader.readObjectToRoiMap(roiData);
			else roiMap = new TreeMap<DimensionMap,ROIPlus>();
			
			// Read the images in the IMAGE data object into imageMap
			TreeMap<DimensionMap,String> imageMap = ImageReader.readObjectToImagePathTable(imageData);
			
			
			DimTable filteredTable = null;// imageData.getDimTable().copy();
			// if a maxima color dimension is given
			if(!nuclearDimValue.equals(""))
			{
				filteredTable = imageData.getDimTable().getSubTable(new DimensionMap(colorDimName + "=" + nuclearDimValue));
			}
			else {
				// copy the DimTable from imageData
				filteredTable = imageData.getDimTable().copy();
			}
			
			
			// Declare outputs
			TreeMap<DimensionMap,ROIPlus> outputRoiMap = new TreeMap<DimensionMap,ROIPlus>();
			TreeMap<DimensionMap,String> outputImageMap = new TreeMap<DimensionMap,String>();
			TreeMap<DimensionMap,String> outputFileMap = new TreeMap<DimensionMap,String>();
			TreeMap<DimensionMap,Double> outputCountMap = new TreeMap<DimensionMap,Double>();
			
			
			// determine value of total	
			int total;// filteredTable.mapCount() * 4; // if maximaOnly
			if(!maximaOnly & !segDimValue.equals(nuclearDimValue))
			{
				total = filteredTable.mapCount() * 8;
			}
			else if(!maximaOnly)
			{
				total = filteredTable.mapCount() * 5;
			}
			else { // if maximaOnly
				total = filteredTable.mapCount() * 4;
			}
			
			
			Roi roi;
			ROIPlus roip;
			int count = 0, percentage = 0, counter = 0;
			MaximumFinder mf = new MaximumFinder();
			for (DimensionMap map : filteredTable.getMapIterator())
			{
				if(this.isCanceled())
				{
					return false;
				}
				// // Update the display
				count ++;
				percentage = (int) (100 * ((double) (count) / ((double) total)));
				JEXStatics.statusBar.setProgressPercentage(percentage);
				counter ++;
				
				
				ImagePlus im = new ImagePlus(imageMap.get(map));
				FloatProcessor ip = (FloatProcessor) im.getProcessor().convertToFloat();
				im.setProcessor(ip);
				
				if(despeckleR > 0)
				{
					// Smooth the image
					RankFilters rF = new RankFilters();
					rF.rank(ip, despeckleR, RankFilters.MEDIAN);
				}
				if(this.isCanceled())
				{
					return false;
				}
				// // Update the display
				count = count + 1;
				percentage = (int) (100 * ((double) (count) / ((double) total)));
				JEXStatics.statusBar.setProgressPercentage(percentage);
				counter = counter + 1;
				
				if(smoothR > 0)
				{
					// Smooth the image
					RankFilters rF = new RankFilters();
					rF.rank(ip, smoothR, RankFilters.MEAN);
				}
				if(this.isCanceled())
				{
					return false;
				}
				// // Update the display
				count = count + 1;
				percentage = (int) (100 * ((double) (count) / ((double) total)));
				JEXStatics.statusBar.setProgressPercentage(percentage);
				counter = counter + 1;
				
				roi = null;
				roip = null;
				roip = roiMap.get(map);
				if(roip != null)
				{
					boolean isLine = roip.isLine();
					if(isLine)
					{
						return false;
					}
					roi = roip.getRoi();
					im.setRoi(roi);
				}
				
				// // Find the Maxima
				ROIPlus points = (ROIPlus) mf.findMaxima(im.getProcessor(), tolerance, threshold, MaximumFinder.ROI, excludePtsOnEdges, isEDM, roi, lightBackground);
				// // Retain maxima within the optional roi
				PointList filteredPoints = new PointList();
				if(roiProvided && roip.getPointList().size() != 0)
				{
					Shape shape = roip.getShape();
					for (IdPoint p : points.getPointList())
					{
						if(shape.contains(p))
						{
							filteredPoints.add(p.x, p.y);
						}
					}
				}
				else
				{
					filteredPoints = points.getPointList();
				}
				
				// // Create the new ROIPlus
				ROIPlus newRoip = new ROIPlus(filteredPoints, ROIPlus.ROI_POINT);
				DimensionMap tempMap = map.copy();
				if(!nuclearDimValue.equals(""))
				{
					tempMap.remove(colorDimName);
				}
				outputRoiMap.put(tempMap, newRoip);
				
				if(!maximaOnly)
				{
					// // Create the segemented image
					DimensionMap segMap = map.copy();
					segMap.put(colorDimName, segDimValue);
					FloatProcessor toSeg = ip;
					if(!segDimValue.equals(nuclearDimValue))
					{
						if(this.isCanceled())
						{
							return false;
						}
						// // Update the display
						count = count + 1;
						percentage = (int) (100 * ((double) (count) / ((double) total)));
						JEXStatics.statusBar.setProgressPercentage(percentage);
						counter = counter + 1;
						
						toSeg = (FloatProcessor) (new ImagePlus(imageMap.get(segMap))).getProcessor().convertToFloat();
						ImagePlus imToSeg = new ImagePlus("toSeg", toSeg);
						if(despeckleR > 0)
						{
							// Smooth the image
							RankFilters rF = new RankFilters();
							rF.setup(CTC_Filters.MEDIAN, imToSeg);
							rF.rank(toSeg, despeckleR, RankFilters.MEDIAN);
							rF.run(toSeg);
						}
						if(this.isCanceled())
						{
							return false;
						}
						// // Update the display
						count = count + 1;
						percentage = (int) (100 * ((double) (count) / ((double) total)));
						JEXStatics.statusBar.setProgressPercentage(percentage);
						counter = counter + 1;
						
						if(smoothR > 0)
						{
							// Smooth the image
							RankFilters rF = new RankFilters();
							rF.setup(CTC_Filters.MEAN, imToSeg);
							rF.rank(toSeg, smoothR, RankFilters.MEAN);
						}
						if(this.isCanceled())
						{
							return false;
						}
						// // Update the display
						count = count + 1;
						percentage = (int) (100 * ((double) (count) / ((double) total)));
						JEXStatics.statusBar.setProgressPercentage(percentage);
						counter = counter + 1;
					}
					
					// Create the Segmented Image
					ByteProcessor segmentedImage = null;
					if(excludeSegsOnEdges != excludePtsOnEdges)
					{
						// // Find the Maxima again with the correct exclusion criteria
						points = (ROIPlus) mf.findMaxima(im.getProcessor(), tolerance, threshold, MaximumFinder.ROI, excludeSegsOnEdges, isEDM, roi, lightBackground);
						segmentedImage = mf.segmentImageUsingMaxima(toSeg, excludeSegsOnEdges);
					}
					else
					{
						// Just use the points we already found
						segmentedImage = mf.segmentImageUsingMaxima(toSeg, excludeSegsOnEdges);
					}
					if(this.isCanceled())
					{
						return false;
					}
					// // Update the display
					count = count + 1;
					percentage = (int) (100 * ((double) (count) / ((double) total)));
					JEXStatics.statusBar.setProgressPercentage(percentage);
					counter = counter + 1;
					
					String segmentedImagePath = JEXWriter.saveImage(segmentedImage);
					if(segmentedImagePath == null)
					{
						Logs.log("Failed to create/write segmented image", Logs.ERROR, this);
					}
					else
					{
						outputImageMap.put(tempMap, segmentedImagePath);
					}
					
					// // Count the maxima
					outputCountMap.put(map, (double) filteredPoints.size());
					
					// // Create the file of XY locations
					String listPath = createXYPointListFile(filteredPoints);
					outputFileMap.put(map, listPath);
					
				}
				
				// // Update the display
				count = count + 1;
				percentage = (int) (100 * ((double) (count) / ((double) total)));
				JEXStatics.statusBar.setProgressPercentage(percentage);
				counter = counter + 1;
				im.flush();
				im = null;
				ip = null;
			}
			if(outputRoiMap.size() == 0)
			{
				return false;
			}
			
			// roi, file file(value), image
			JEXData output0 = RoiWriter.makeRoiObject(this.outputNames[0].getName(), outputRoiMap);
			this.realOutputs.add(output0);
			
			if(!maximaOnly)
			{
				JEXData output1 = FileWriter.makeFileObject(this.outputNames[1].getName(), null, outputFileMap);
				String countsFile = JEXTableWriter.writeTable(this.outputNames[2].getName(), outputCountMap, "arff");
				JEXData output2 = FileWriter.makeFileObject(this.outputNames[2].getName(), null, countsFile);
				JEXData output3 = ImageWriter.makeImageStackFromPaths(this.outputNames[3].getName(), outputImageMap);
				
				this.realOutputs.add(output1);
				this.realOutputs.add(output2);
				this.realOutputs.add(output3);
			}
			
			// Return status
			return true;
		}
		catch (Exception e)
		{
			e.printStackTrace();
			return false;
		}
	}
	
	// private String saveAdjustedImage(String imagePath, double oldMin, double
	// oldMax, double newMin, double newMax, double gamma, int bitDepth)
	// {
	// // Get image data
	// File f = new File(imagePath);
	// if(!f.exists()) return null;
	// ImagePlus im = new ImagePlus(imagePath);
	// FloatProcessor imp = (FloatProcessor) im.getProcessor().convertToFloat();
	// // should be a float processor
	//
	// // Adjust the image
	// FunctionUtility.imAdjust(imp, oldMin, oldMax, newMin, newMax, gamma);
	//
	// // Save the results
	// ImagePlus toSave = FunctionUtility.makeImageToSave(imp, "false",
	// bitDepth);
	// String imPath = JEXWriter.saveImage(toSave);
	// im.flush();
	//
	// // return temp filePath
	// return imPath;
	// }
	
	public static String createXYPointListFile(PointList pl)
	{
		JEXCSVWriter w = new JEXCSVWriter(JEXWriter.getDatabaseFolder() + File.separator + JEXWriter.getUniqueRelativeTempPath("csv"));
		w.write(new String[] { "ID", "X", "Y" });
		for (IdPoint p : pl)
		{
			w.write(new String[] { "" + p.id, "" + p.x, "" + p.y });
		}
		w.close();
		return w.path;
	}
}