package com.marginallyclever.makelangeloRobot.loadAndSave;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Random;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;


import javax.swing.filechooser.FileNameExtensionFilter;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import com.marginallyclever.makelangelo.Log;
import com.marginallyclever.makelangelo.Translator;
import com.marginallyclever.makelangeloRobot.ImageManipulator;
import com.marginallyclever.makelangeloRobot.MakelangeloRobot;
import com.marginallyclever.makelangeloRobot.generators.Turtle;

/**
 * LoadAndSaveSB3 loads limited set of Scratch commands into memory. 
 * @author Admin
 *
 */
public class LoadAndSaveScratch3 extends ImageManipulator implements LoadAndSaveFileType {
	private final String PROJECT_JSON = "project.json";
	
	private class ScratchVariable {
		public String name;
		public String uniqueID;
		public float value;

		public ScratchVariable(String _name,String _id,float _val) {
			name=_name;
			uniqueID=_id;
			value=_val;
		}
	};
	private class ScratchList {
		public String name;
		public ArrayList<Float> contents;

		public ScratchList(String _name) {
			name=_name;
			contents=new ArrayList<Float>();
		}
	};
	

	private static final Set<String> IMAGE_FILE_EXTENSIONS;
	static {
		IMAGE_FILE_EXTENSIONS = new HashSet<>();
		IMAGE_FILE_EXTENSIONS.add("SB3");
	}
	
	private FileNameExtensionFilter filter = new FileNameExtensionFilter(Translator.get("FileTypeScratch3"),
			IMAGE_FILE_EXTENSIONS.toArray(new String[IMAGE_FILE_EXTENSIONS.size()]));
	private Turtle turtle;
	private LinkedList<ScratchVariable> scratchVariables;
	private LinkedList<ScratchList> scratchLists;
	//private int indent=0;
	private boolean penUp=false;
	
	@Override
	public FileNameExtensionFilter getFileNameFilter() {
		return filter;
	}

	@Override
	public boolean canLoad(String filename) {
		String filenameExtension = filename.substring(filename.lastIndexOf('.'));
		return IMAGE_FILE_EXTENSIONS.contains(filenameExtension.toLowerCase());
	}

	@Override
	public boolean canSave(String filename) {
		return false;
	}

	
	@Override
	public boolean load(InputStream in,MakelangeloRobot robot) {
		Log.info(Translator.get("FileTypeSB3")+"...");
		// set up a temporary file
		File tempGCodeFile;
		try {
			tempGCodeFile = File.createTempFile("temp", ".ngc");
		} catch (IOException e1) {
			e1.printStackTrace();
			return false;
		}
		tempGCodeFile.deleteOnExit();
		Log.info(Translator.get("Converting") + " " + tempGCodeFile.getName());

		try (FileOutputStream fileOutputStream = new FileOutputStream(tempGCodeFile);
				Writer out = new OutputStreamWriter(fileOutputStream, StandardCharsets.UTF_8))
		{
			if(robot.getSettings().isReverseForGlass()) {
				Log.info("Flipping for glass...");
			}

			machine = robot.getSettings();
			
			try {
				// open zip file
	        	System.out.println("Searching for project.json...");
	        	
				ZipInputStream zipInputStream = new ZipInputStream(in);
				
				// locate project.json
				ZipEntry entry;
				File tempZipFile=null;
				boolean found=false;
				while((entry = zipInputStream.getNextEntry())!=null) {
			        if( entry.getName().equals(PROJECT_JSON) ) {
			        	System.out.println("Found project.json...");
			        	
				        // read buffered stream into temp file.
			        	tempZipFile = File.createTempFile("project", "json");
			        	tempZipFile.setReadable(true);
			        	tempZipFile.setWritable(true);
			        	tempZipFile.deleteOnExit();
				        FileOutputStream fos = new FileOutputStream(tempZipFile);
			    		byte[] buffer = new byte[2048];
			    		int len;
		                while ((len = zipInputStream.read(buffer)) > 0) {
		                    fos.write(buffer, 0, len);
		                }
		                fos.close();
		                found=true;
		                break;
			        }
				}

				if(found==false) {
					throw new Exception("SB3 missing project.json");
				}
				
    			// parse JSON
                System.out.println("Parsing JSON file...");
                
	        	JSONParser parser = new JSONParser();
    			JSONObject tree = (JSONObject)parser.parse(new FileReader(tempZipFile));
    			// we're done with the tempZipFile now that we have the JSON structure.
    			tempZipFile.delete();
    			
    			// reset the turtle object
    			turtle = new Turtle();
    			
    			// make sure machine state is the default.
    			imageStart(out);
    			
    			if(confirmAtLeastVersion3(tree)==false) {
    				// TODO add popup dialog warning
    				return false;
    			}
    			
    			readScratchVariables(tree);
    			readScratchLists(tree);
    			readScratchInstructions(tree, out);
    			
    			imageEnd(out);
    			
    			System.out.println("finished scripts");
			} catch (Exception e) {
				Log.error(Translator.get("LoadError") +" "+ e.getLocalizedMessage());
				e.printStackTrace();
				return false;
			}
			
			// finished. Close up file.
			out.flush();
			out.close();

			// now load the gcode.
			LoadAndSaveGCode loader = new LoadAndSaveGCode();
			InputStream fileInputStream = new FileInputStream(tempGCodeFile);
			loader.load(fileInputStream,robot);
		} catch (IOException e) {
			e.printStackTrace();
		}
	
		tempGCodeFile.delete();
		
		return true;
	}


	/**
	 * parse blocks in scratch
	 * @param tree the JSONObject tree read from the project.json/zip file.
	 * @throws Exception
	 */
	private void readScratchInstructions(JSONObject tree,Writer out) throws Exception {
		scratchVariables = new LinkedList<ScratchVariable>();
		JSONArray targets = (JSONArray)tree.get("targets");
		ListIterator<?> targetIter = targets.listIterator();
		while(targetIter.hasNext()) {
			JSONObject targetN = (JSONObject)targetIter.next();
			if( (boolean)targetN.get("isStage") == true ) continue;
			JSONObject blocks = (JSONObject)targetN.get("blocks");

			System.out.println("found  " +blocks.size() + " blocks");
		}
		/*
		// extract known elements and convert them to gcode.
		ListIterator<?> scriptIter = scripts.listIterator();
		// find the script with the green flag
		while( scriptIter.hasNext() ) {
			JSONArray scripts0 = (JSONArray)scriptIter.next();
			if( scripts0==null ) continue;
			//System.out.println("scripts0");
			JSONArray scripts02 = (JSONArray)scripts0.get(2);
			if( scripts02==null || scripts02.size()==0 ) continue;
			//System.out.println("scripts02");
			// actual code begins here.
			parseScratchCode(scripts02,out);
		}*/
	}

	/**
	 * confirm this is version 3
	 * @param tree the JSONObject tree read from the project.json/zip file.
	 * @throws Exception
	 */
	private boolean confirmAtLeastVersion3(JSONObject tree) throws Exception {
		JSONObject meta = (JSONObject)tree.get("meta");
		if(meta==null) return false;
		
		String semver = (String)meta.get("semver");
		if(semver==null) return false;
		
		return ( semver.compareTo("3.0.0") <= 0 ); 
	}
	
	/**
	 * read the list of Scratch variables
	 * @param tree the JSONObject tree read from the project.json/zip file.
	 * @throws Exception
	 */
	private void readScratchVariables(JSONObject tree) throws Exception {
		scratchVariables = new LinkedList<ScratchVariable>();
		JSONArray targets = (JSONArray)tree.get("targets");
		ListIterator<?> targetIter = targets.listIterator();
		while(targetIter.hasNext()) {
			JSONObject targetN = (JSONObject)targetIter.next();
			if( (boolean)targetN.get("isStage") == false ) continue;
			
			JSONObject variables = (JSONObject)targetN.get("variables");
			Iterator<String> keys = variables.keySet().iterator();
			while(keys.hasNext()) {
				String k=keys.next();
				JSONArray details = (JSONArray)variables.get(k);
				String name = (String)details.get(0);
				Number value = (Number)details.get(1);
				try {
					scratchVariables.add(new ScratchVariable(name,k,value.floatValue()));
				} catch (Exception e) {
					throw new Exception("Variables must be numbers.");
				}
			}
		}
		System.out.println(scratchVariables);
	}

	/**
	 * read the list of Scratch lists
	 * @param tree the JSONObject tree read from the project.json/zip file.
	 * @throws Exception
	 */
	private void readScratchLists(JSONObject tree) throws Exception {
		scratchLists = new LinkedList<ScratchList>();
		JSONArray targets = (JSONArray)tree.get("targets");
		ListIterator<?> targetIter = targets.listIterator();
		while(targetIter.hasNext()) {
			JSONObject targetN = (JSONObject)targetIter.next();
			if( (boolean)targetN.get("isStage") == false ) continue;
			JSONObject listOfLists = (JSONObject)targetN.get("lists");
		}
		/*
		if(listOfLists == null) return;
		ListIterator<JSONObject> listIter = listOfLists.listIterator();
		while( listIter.hasNext() ) {
			//System.out.println("var:"+elem.toString());
			JSONObject elem = listIter.next();
			String listName = (String)elem.get("name");
			Object contents = (Object)elem.get("contents");
			ScratchList list = new ScratchList(listName);
			// fill the list with any given contents
			if( contents != null && contents instanceof JSONArray ) {
				JSONArray arr = (JSONArray)contents;

				ListIterator<Object> scriptIter = arr.listIterator();
				while(scriptIter.hasNext()) {
					Object varValue = scriptIter.next();
					float value;
					if(varValue instanceof Number) {
						Number num = (Number)varValue;
						value = (float)num.doubleValue();
						list.contents.add(value);
					} else if(varValue instanceof String) {
						try {
							value = Float.parseFloat((String)varValue);
							list.contents.add(value);
						} catch (Exception e) {
							throw new Exception("List variables must be numbers.");
						}
					} else throw new Exception("List variable "+listName+"("+list.contents.size()+") is "+varValue.toString());
				}
			}
			// add the list to the list-of-lists.
			scratchLists.add(list);
		}*/
	}
	
	private int getListID(Object obj) throws Exception {
		if(!(obj instanceof String)) throw new Exception("List name not a string.");
		String listName = obj.toString();
		ListIterator<ScratchList> iter = scratchLists.listIterator();
		int index=0;
		while(iter.hasNext()) {
			ScratchList i = iter.next();
			if(i.name.equals(listName)) return index;
			++index;
		}
		throw new Exception("List '"+listName+"' not found.");
	}
	
	/**
	 * read the elements of a JSON array describing Scratch code and parse it into gcode.
	 * @param script valid JSONArray of Scratch commands.
	 * @param out where to put the gcode.
	 * @throws Exception
	 */
	private void parseScratchCode(JSONArray script,Writer out) throws Exception {
		if(script==null) return;
		
		//for(int j=0;j<indent;++j) System.out.print("  ");
		//System.out.println("size="+script.size());
		//indent++;
		
		ListIterator<?> scriptIter = script.listIterator();
		// find the script with the green flag
		while( scriptIter.hasNext() ) {
			Object o = (Object)scriptIter.next();
			if( o instanceof JSONArray ) {
				JSONArray arr = (JSONArray)o;
				parseScratchCode(arr,out);
			} else {
				String name = o.toString();
				//for(int j=0;j<indent;++j) System.out.print("  ");
				//System.out.println(i+"="+name);
				
				if(name.equals("whenGreenFlag")) {
					// gcode preamble
	    			// reset the turtle object
	    			turtle = new Turtle();
	    			// make sure machine state is the default.
	    			setAbsoluteMode(out);
					System.out.println("**START**");
					continue;
				} else if(name.equals("doRepeat")) {
					Object o2 = (Object)scriptIter.next();
					Object o3 = (Object)scriptIter.next();
					int count = (int)resolveValue(o2);
					//System.out.println("Repeat "+count+" times:");
					for(int i=0;i<count;++i) {
						parseScratchCode((JSONArray)o3,out);
					}
				} else if(name.equals("doUntil")) {
					Object o2 = (Object)scriptIter.next();
					Object o3 = (Object)scriptIter.next();
					//System.out.println("Do Until {");
					while(!resolveBoolean((JSONArray)o2)) {
						parseScratchCode((JSONArray)o3,out);
					}
					//System.out.println("}");
				} else if(name.equals("doIf")) {
					Object o2 = (Object)scriptIter.next();
					Object o3 = (Object)scriptIter.next();
					if(resolveBoolean((JSONArray)o2)) {
						parseScratchCode((JSONArray)o3,out);
					}
				} else if(name.equals("doIfElse")) {
					Object o2 = (Object)scriptIter.next();
					Object o3 = (Object)scriptIter.next();
					Object o4 = (Object)scriptIter.next();
					if(resolveBoolean((JSONArray)o2)) {
						parseScratchCode((JSONArray)o3,out);
					} else {
						parseScratchCode((JSONArray)o4,out);
					}
				} else if(name.equals("append:toList:")) {
					// "append:toList:", new value, list name 
					Object o2 = (Object)scriptIter.next();
					Object o3 = (Object)scriptIter.next();
					float value = resolveValue(o2);
					scratchLists.get(getListID(o3)).contents.add(value);
				} else if(name.equals("deleteLine:ofList:")) {
					// "deleteLine:ofList:", index, list name 
					Object o2 = (Object)scriptIter.next();
					Object o3 = (Object)scriptIter.next();
					int listIndex = (int)resolveListIndex(o2,o3);
					scratchLists.get(getListID(o3)).contents.remove(listIndex);
				} else if(name.equals("insert:at:ofList:")) {
					// "insert:at:ofList:", new value, index, list name 
					Object o4 = (Object)scriptIter.next();
					Object o2 = (Object)scriptIter.next();
					Object o3 = (Object)scriptIter.next();
					float newValue = resolveValue(o4);
					int listIndex = (int)resolveListIndex(o2,o3);
					scratchLists.get(getListID(o3)).contents.add(listIndex,newValue);
				} else if(name.equals("setLine:ofList:to:")) {
					// "setLine:ofList:to:", index, list name, new value
					Object o4 = (Object)scriptIter.next();
					Object o2 = (Object)scriptIter.next();
					Object o3 = (Object)scriptIter.next();
					float newValue = resolveValue(o4);
					int listIndex = (int)resolveListIndex(o2,o3);
					scratchLists.get(getListID(o3)).contents.set(listIndex,newValue);
				} else if(name.equals("wait:elapsed:from:")) {
					// dwell - does nothing.
					Object o2 = (Object)scriptIter.next();
					float seconds = resolveValue(o2);
					System.out.println("dwell "+seconds+" seconds.");
					continue;
				} else if(name.equals("putPenUp")) {
					penUp=true;
					this.liftPen(out);
					System.out.println("pen up");
					continue;
				} else if(name.equals("putPenDown")) {
					penUp=false;
					this.lowerPen(out);
					System.out.println("pen down");
				} else if(name.equals("gotoX:y:")) {
					Object o2 = (Object)scriptIter.next();
					float x = resolveValue(o2);
					Object o3 = (Object)scriptIter.next();
					float y = resolveValue(o3);
					
					turtle.setX(x);
					turtle.setY(y);
					this.moveTo(out, turtle.getX(), turtle.getY(), penUp);
					System.out.println("Move to ("+turtle.getX()+","+turtle.getY()+")");
				} else if(name.equals("changeXposBy:")) {
					Object o2 = (Object)scriptIter.next();
					float v = resolveValue(o2);
					turtle.setX(turtle.getX()+v);
					this.moveTo(out, turtle.getX(), turtle.getY(), penUp);
					//System.out.println("Move to ("+turtle.getX()+","+turtle.getY()+")");
				} else if(name.equals("changeYposBy:")) {
					Object o2 = (Object)scriptIter.next();
					float v = resolveValue(o2);
					turtle.setY(turtle.getY()+v);
					this.moveTo(out, turtle.getX(), turtle.getY(), penUp);
					//System.out.println("Move to ("+turtle.getX()+","+turtle.getY()+")");
				} else if(name.equals("forward:")) {
					Object o2 = (Object)scriptIter.next();
					float v = resolveValue(o2);
					turtle.move(v);
					this.moveTo(out, turtle.getX(), turtle.getY(), penUp);
					System.out.println("Move forward "+v+" mm");
				} else if(name.equals("turnRight:")) {
					Object o2 = (Object)scriptIter.next();
					float degrees = resolveValue(o2);
					turtle.turn(-degrees);
					System.out.println("Right "+degrees+" degrees.");
				} else if(name.equals("turnLeft:")) {
					Object o2 = (Object)scriptIter.next();
					float degrees = resolveValue(o2);
					turtle.turn(degrees);
					System.out.println("Left "+degrees+" degrees.");
				} else if(name.equals("xpos:")) {
					Object o2 = (Object)scriptIter.next();
					float v = resolveValue(o2);
					turtle.setX(v);
					this.moveTo(out, turtle.getX(), turtle.getY(), penUp);
					//System.out.println("Move to ("+turtle.getX()+","+turtle.getY()+")");
				} else if(name.equals("ypos:")) {
					Object o2 = (Object)scriptIter.next();
					float v = resolveValue(o2);
					turtle.setY(v);
					this.moveTo(out, turtle.getX(), turtle.getY(), penUp);
					//System.out.println("Move to ("+turtle.getX()+","+turtle.getY()+")");
				} else if(name.equals("heading:")) {
					Object o2 = (Object)scriptIter.next();
					float degrees = resolveValue(o2);
					turtle.setAngle(degrees);
					//System.out.println("Turn to "+degrees);
				} else if(name.equals("setVar:to:")) {
					// set variable
					String varName = (String)scriptIter.next();
					Object o3 = (Object)scriptIter.next();
					float v = (float)resolveValue(o3);

					boolean foundVar=false;
					ListIterator<ScratchVariable> svi = scratchVariables.listIterator();
					while(svi.hasNext()) {
						ScratchVariable sv = svi.next();
						if(sv.name.equals(varName)) {
							sv.value = v;
							System.out.println("Set "+varName+" to "+v);
							foundVar=true;
						}
					}
					if(foundVar==false) {
						throw new Exception("Variable '"+varName+"' not found.");
					}
				} else if(name.equals("changeVar:by:")) {
					// set variable
					String varName = (String)scriptIter.next();
					Object o3 = (Object)scriptIter.next();
					float v = (float)resolveValue(o3);

					boolean foundVar=false;
					ListIterator<ScratchVariable> svi = scratchVariables.listIterator();
					while(svi.hasNext()) {
						ScratchVariable sv = svi.next();
						if(sv.name.equals(varName)) {
							sv.value += v;
							System.out.println("Change "+varName+" by "+v+" to "+sv.value);
							foundVar=true;
						}
					}
					if(foundVar==false) {
						throw new Exception("Variable '"+varName+"' not found.");
					}
				} else if(name.equals("clearPenTrails")) {
					// Ignore this Scratch command
				} else if(name.equals("hide")) {
					// Ignore this Scratch command
				} else if(name.equals("show")) {
					// Ignore this Scratch command
				} else {
					throw new Exception("Unsupported Scratch block '"+name+"'");
				}
			}
		}
		//indent--;
	}
	
	/**
	 * Scratch block contains a boolean or boolean operator
	 * @param obj a String, Number, or JSONArray of elements to be calculated. 
	 * @return the calculated final value.
	 * @throws Exception
	 */
	private boolean resolveBoolean(Object obj) throws Exception {
		if(!(obj instanceof JSONArray)) {
			throw new Exception("Parse error (resolveBoolean not array)");
		}
		JSONArray arr=(JSONArray)obj;
		ListIterator<?> scriptIter = arr.listIterator();
		Object first = scriptIter.next();
		String name = first.toString();
		if(name.equals(">")) {
			Object o2 = (Object)scriptIter.next();
			Object o3 = (Object)scriptIter.next();
			float a = resolveValue(o2);
			float b = resolveValue(o3);
			return a > b;
		}
		if(name.equals("<")) {
			Object o2 = (Object)scriptIter.next();
			Object o3 = (Object)scriptIter.next();
			float a = resolveValue(o2);
			float b = resolveValue(o3);
			return a < b;
		}
		if(name.equals("=")) {
			Object o2 = (Object)scriptIter.next();
			Object o3 = (Object)scriptIter.next();
			float a = resolveValue(o2);
			float b = resolveValue(o3);
			return a == b; 
		}
		if(name.equals("not")) {
			Object o2 = (Object)scriptIter.next();
			return !resolveBoolean(o2);
		}
		if(name.equals("&")) {
			Object o2 = (Object)scriptIter.next();
			Object o3 = (Object)scriptIter.next();
			return resolveBoolean(o2) && resolveBoolean(o3);
		}
		if(name.equals("|")) {
			Object o2 = (Object)scriptIter.next();
			Object o3 = (Object)scriptIter.next();
			return resolveBoolean(o2) || resolveBoolean(o3);
		}
		
		throw new Exception("Parse error (resolveBoolean unsupported)");
	}
	
	/**
	 * Scratch block contains an Operator (variable, constant, or math combination of the two). 
	 * @param obj a String, Number, or JSONArray of elements to be calculated.
	 * @return the calculated final value.
	 * @throws Exception
	 */
	private float resolveValue(Object obj) throws Exception {
		if(obj instanceof String) {
			// probably a variable
			String firstName = obj.toString();
			
			if(firstName.equals("xpos")) {
				return turtle.getX();
			}
			if(firstName.equals("ypos")) {
				return turtle.getY();
			}
			if(firstName.equals("heading")) {
				return turtle.getAngle();
			}

			try {
				float v = Float.parseFloat(firstName);
				return v;
			} catch (Exception e) {
				throw new Exception("Unresolved string value '"+obj.toString()+"'");
			}
		}
		
		if(obj instanceof Number) {
			Number num = (Number)obj;
			return (float)num.doubleValue();
		}
		
		if(obj instanceof JSONArray) {
			JSONArray arr=(JSONArray)obj;
			ListIterator<?> scriptIter = arr.listIterator();
			Object first = scriptIter.next();
			if(!(first instanceof String)) {
				throw new Exception("Parse error (resolveValue array)");
			}
			String firstName = first.toString();
			if(firstName.equals("/")) {
				// divide
				Object o2 = (Object)scriptIter.next();
				Object o3 = (Object)scriptIter.next();
				float a = (float)resolveValue(o2);
				float b = (float)resolveValue(o3);
				return a/b;
			}
			if(firstName.equals("*")) {
				// multiply
				Object o2 = (Object)scriptIter.next();
				Object o3 = (Object)scriptIter.next();
				float a = (float)resolveValue(o2);
				float b = (float)resolveValue(o3);
				return a*b;
			}
			if(firstName.equals("+")) {
				// add
				Object o2 = (Object)scriptIter.next();
				Object o3 = (Object)scriptIter.next();
				float a = (float)resolveValue(o2);
				float b = (float)resolveValue(o3);
				return a+b;
			}
			if(firstName.equals("-")) {
				// subtract
				Object o2 = (Object)scriptIter.next();
				Object o3 = (Object)scriptIter.next();
				float a = (float)resolveValue(o2);
				float b = (float)resolveValue(o3);
				return a-b;
			}
			if(firstName.equals("randomFrom:to:")) {
				Object o2 = (Object)scriptIter.next();
				Object o3 = (Object)scriptIter.next();
				int a = (int)resolveValue(o2);
				int b = (int)resolveValue(o3);
				if(a>b) {
					int c = b;
					b=a;
					a=c;
				}
				Random r = new Random();
				return r.nextInt(b-a)+a;
			}
			if(firstName.equals("readVariable")) {
				String varName = (String)scriptIter.next();

				ListIterator<ScratchVariable> svi = scratchVariables.listIterator();
				while(svi.hasNext()) {
					ScratchVariable sv = svi.next();
					if(sv.name.equals(varName)) {
						return sv.value;
					}
				}
			}
			if(firstName.equals("computeFunction:of:")) {
				String functionName = (String)scriptIter.next();
				Object o2 = (Object)scriptIter.next();
				
				float a = (float)resolveValue(o2);

				if(functionName.equals("abs")) return (float)Math.abs(a);
				if(functionName.equals("floor")) return (float)Math.floor(a);
				if(functionName.equals("ceiling")) return (float)Math.ceil(a);
				if(functionName.equals("sqrt")) return (float)Math.sqrt(a);
				if(functionName.equals("sin")) return (float)Math.sin(Math.toRadians(a));
				if(functionName.equals("cos")) return (float)Math.cos(Math.toRadians(a));
				if(functionName.equals("tan")) return (float)Math.tan(Math.toRadians(a));

				if(functionName.equals("asin")) return (float)Math.asin(Math.toRadians(a));
				if(functionName.equals("acos")) return (float)Math.acos(Math.toRadians(a));
				if(functionName.equals("atan")) return (float)Math.atan(Math.toRadians(a));
				if(functionName.equals("ln")) return (float)Math.log(a);
				if(functionName.equals("log")) return (float)Math.log10(a);
				if(functionName.equals("e ^")) return (float)Math.pow(Math.E,a);
				if(functionName.equals("10 ^")) return (float)Math.pow(10,a);
				throw new Exception("Parse error (resolveValue computeFunction)");
			}
			if(firstName.equals("lineCountOfList:")) {
				String listName = (String)scriptIter.next();
				return scratchLists.get(getListID(listName)).contents.size();
			}
			if(firstName.equals("getLine:ofList:")) {
				Object o2 = scriptIter.next();
				Object o3 = scriptIter.next();
				int listIndex = resolveListIndex(o2,o3);
				String listName = (String)o3;
				ScratchList list = scratchLists.get(getListID(listName)); 

				return list.contents.get(listIndex);
			}
			
			return resolveValue(first);
		}

		throw new Exception("Parse error (resolveValue)");
	}
	
	/**
	 * Find the requested index in a list.
	 * @param o2 the index value.  could be "random", "last", or an index number
	 * @param o3 the list name.
	 * @return the resolved value as an integer.
	 * @throws Exception
	 */
	private int resolveListIndex(Object o2,Object o3) throws Exception {
		String index = (String)o2;
		String listName = (String)o3;
		ScratchList list = scratchLists.get(getListID(listName)); 
		int listIndex;
		if(index.equals("last")) {
			listIndex = list.contents.size()-1;
		} else if(index.equals("random")) {
			listIndex = (int) (Math.random() * list.contents.size());
		} else {
			listIndex = Integer.parseInt(index);
		}

		return listIndex;
	}
	
	@Override
	public boolean save(OutputStream outputStream,MakelangeloRobot robot) {
		return true;
	}

	@Override
	public boolean canLoad() {
		return true;
	}

	@Override
	public boolean canSave() {
		return false;
	}
}
