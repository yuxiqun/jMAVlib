package me.drton.jmavlib.log.ulog;

import java.io.EOFException;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.swing.JFileChooser;

import me.drton.jmavlib.log.BinaryLogReader;
import me.drton.jmavlib.log.FormatErrorException;

/**
 * User: ton Date: 03.06.13 Time: 14:18
 */
public class ULogReader extends BinaryLogReader {
    static final byte MESSAGE_TYPE_FORMAT = (byte) 'F';
    static final byte MESSAGE_TYPE_DATA = (byte) 'D';
    static final byte MESSAGE_TYPE_INFO = (byte) 'I';
    static final byte MESSAGE_TYPE_PARAMETER = (byte) 'P';
    static final int HDRLEN = 3;
    static final int FILE_MAGIC_HEADER_LENGTH = 16;

    private String systemName = "PX4";
    private long dataStart = 0;
    private Map<Integer, MessageFormat> messageFormats
            = new HashMap<Integer, MessageFormat>();
    private Map<String, String> fieldsList = null;
    private long sizeUpdates = -1;
    private long sizeMicroseconds = -1;
    private long startMicroseconds = -1;
    private long utcTimeReference = -1;
    private long logStartTimestamp = -1;
    private Map<Integer, Integer> maxMultiID = new HashMap<Integer, Integer>();
    private Map<String, Object> version = new HashMap<String, Object>();
    private Map<String, Object> parameters = new HashMap<String, Object>();

    public Map<String, List<ParamUpdate>> parameterUpdates;
    public class ParamUpdate {
        private String name;
        private Object value;
        private long timestamp = -1;
        private ParamUpdate(String nm, Object v, long ts) {
            name = nm;
            value = v;
            timestamp = ts;
        }

        public String getName() {
            return name;
        }

        public Object getValue() {
            return value;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }
    private List<Exception> errors = new ArrayList<Exception>();

    /** Index for fast(er) seeking */
    private ArrayList<SeekTime> seekTimes = null;

    private class SeekTime {
        public SeekTime(long t, long pos) {
            timestamp = t;
            position = pos;
        }

        public long timestamp;
        public long position;
    }

    public ULogReader(String fileName) throws IOException, FormatErrorException {
        super(fileName);
        parameterUpdates = new HashMap<String, List<ParamUpdate>>();
        updateStatistics();
    }

    @Override
    public String getFormat() {
        return "ULog";
    }

    public String getSystemName() {
        return systemName;
    }

    @Override
    public long getSizeUpdates() {
        return sizeUpdates;
    }

    @Override
    public long getStartMicroseconds() {
        return startMicroseconds;
    }

    @Override
    public long getSizeMicroseconds() {
        return sizeMicroseconds;
    }

    @Override
    public long getUTCTimeReferenceMicroseconds() {
        return utcTimeReference;
    }

    @Override
    public Map<String, Object> getVersion() {
        return version;
    }

    @Override
    public Map<String, Object> getParameters() {
        return parameters;
    }

    /**
     * Read and parse the file header.
     * 
     * @throws IOException
     * @throws FormatErrorException
     */
    private void readFileHeader() throws IOException, FormatErrorException {
        fillBuffer(FILE_MAGIC_HEADER_LENGTH);
        //magic + version
        boolean error = false;
        if ((buffer.get() & 0xFF) != 'U')
            error = true;
        if ((buffer.get() & 0xFF) != 'L')
            error = true;
        if ((buffer.get() & 0xFF) != 'o')
            error = true;
        if ((buffer.get() & 0xFF) != 'g')
            error = true;
        if ((buffer.get() & 0xFF) != 0x01)
            error = true;
        if ((buffer.get() & 0xFF) != 0x12)
            error = true;
        if ((buffer.get() & 0xFF) != 0x35)
            error = true;
        if ((buffer.get() & 0xFF) != 0x00 && !error) {
            System.out.println("ULog: Different version than expected. Will try anyway");
        }
        if (error)
            throw new FormatErrorException("ULog: Wrong file format");

        logStartTimestamp = buffer.getLong();
    }

    /**
     * Read all necessary information from the file, including message formats,
     * seeking positions and log file information.
     * 
     * @throws IOException
     * @throws FormatErrorException
     */
    private void updateStatistics() throws IOException, FormatErrorException {
        position(0);
        readFileHeader();
        long packetsNum = 0;
        long timeStart = -1;
        long timeEnd = -1;
        long lastTime = -1;
        fieldsList = new HashMap<String, String>();
        seekTimes = new ArrayList<SeekTime>();
        while (true) {
            Object msg;
            long pos = position();
            try {
                msg = readMessage();
            } catch (EOFException e) {
                break;
            }
            packetsNum++;

            if (msg instanceof MessageFormat) {
                MessageFormat msgFormat = (MessageFormat) msg;
                messageFormats.put(msgFormat.msgID, msgFormat);

            } else if (msg instanceof MessageParameter) {
                MessageParameter msgParam = (MessageParameter) msg;
                if (parameters.containsKey(msgParam.getKey())) {
                    System.out.println("update to parameter: " + msgParam.getKey() + " value: " + msgParam.value + " at t = " + lastTime);
                    // maintain a record of parameters which change during flight
                    if (parameterUpdates.containsKey(msgParam.getKey())) {
                        parameterUpdates.get(msgParam.getKey()).add(new ParamUpdate(msgParam.getKey(), msgParam.value, lastTime));
                    } else {
                        List<ParamUpdate> updateList = new ArrayList<ParamUpdate>();
                        updateList.add(new ParamUpdate(msgParam.getKey(), msgParam.value, lastTime));
                        parameterUpdates.put(msgParam.getKey(), updateList);
                    }
                } else {
                    // add parameter to the parameters Map
                    parameters.put(msgParam.getKey(), msgParam.value);
                }

            } else if (msg instanceof MessageInfo) {
                MessageInfo msgInfo = (MessageInfo) msg;
                if ("sys_name".equals(msgInfo.getKey())) {
                    systemName = (String) msgInfo.value;
                } else if ("ver_hw".equals(msgInfo.getKey())) {
                    version.put("HW", msgInfo.value);
                } else if ("ver_sw".equals(msgInfo.getKey())) {
                    version.put("FW", msgInfo.value);
                } else if ("time_ref_utc".equals(msgInfo.getKey())) {
                    utcTimeReference = ((long) ((Number) msgInfo.value).intValue()) * 1000 * 1000;
                }

            } else if (msg instanceof MessageData) {
                if (dataStart == 0) {
                    dataStart = pos;
                }
                MessageData msgData = (MessageData) msg;
                seekTimes.add(new SeekTime(msgData.timestamp, pos));

                if (timeStart < 0) {
                    timeStart = msgData.timestamp;
                }
                if (timeEnd < msgData.timestamp) timeEnd = msgData.timestamp;
                lastTime = msgData.timestamp;
                int msgID = msgData.format.msgID;
                if (maxMultiID.containsKey(msgID)) {
                    if (maxMultiID.get(msgID) < msgData.multiID) maxMultiID.put(msgID, msgData.multiID);
                } else {
                    maxMultiID.put(msgID, msgData.multiID);
                }
            }
        }
        // make a second pass filling the fieldsList now that we know how many multi-instances are in the log
        position(FILE_MAGIC_HEADER_LENGTH);
        while (true) {
            Object msg;
            try {
                msg = readMessage();
            } catch (EOFException e) {
                break;
            }
            if (msg instanceof MessageFormat) {
                MessageFormat msgFormat = (MessageFormat) msg;
                if (msgFormat.name.charAt(0) != '_') {
                    try {
                        int maxInstance = maxMultiID.get(msgFormat.msgID);
                        for (int i = 0; i < msgFormat.fields.length; i++) {
                            FieldFormat fieldDescr = msgFormat.fields[i];
                            if (!fieldDescr.name.startsWith("_padding") && fieldDescr.name != "timestamp") {
                                for (int mid = 0; mid <= maxInstance; mid++) {
                                    if (fieldDescr.isArray()) {
                                        for (int j = 0; j < fieldDescr.size; j++) {
                                            fieldsList.put(msgFormat.name + "_" + mid + "." + fieldDescr.name + "[" + j + "]", fieldDescr.type);
                                        }
                                    } else {
                                        fieldsList.put(msgFormat.name + "_" + mid + "." + fieldDescr.name, fieldDescr.type);
                                    }
                                }
                            }
                        }
                    } catch (NullPointerException e) {
                        continue;
                    }
                }
            }
            if (msg instanceof MessageData) {
                break;
            }
        }
        startMicroseconds = timeStart;
        sizeUpdates = packetsNum;
        sizeMicroseconds = timeEnd - timeStart;
        seek(0);
    }

    @Override
    public boolean seek(long seekTime) throws IOException, FormatErrorException {
        position(dataStart);
        if (seekTime == 0) {      // Seek to start of log
            return true;
        }

        //find the position in seekTime. We could speed this up further by
        //using a binary search
        for (SeekTime sk : seekTimes) {
            if (sk.timestamp >= seekTime) {
                position(sk.position);
                return true;
            }
        }
        return false;
    }

    private void applyMsg(Map<String, Object> update, MessageData msg) {
        applyMsgAsName(update, msg, msg.format.name + "_" + msg.multiID);
//        if (msg.isActive) {
//            applyMsgAsName(update, msg, msg.format.name);
//        }
    }

    void applyMsgAsName(Map<String, Object> update, MessageData msg, String msg_name) {
        FieldFormat[] fields = msg.format.fields;
        for (int i = 0; i < fields.length; i++) {
            FieldFormat field = fields[i];
            if (field.isArray()) {
                for (int j = 0; j < field.size; j++) {
                    update.put(msg_name + "." + field.name + "[" + j + "]", ((Object[]) msg.get(i))[j]);
                }
            } else {
                update.put(msg_name + "." + field.name, msg.get(i));
            }
        }
    }

    @Override
    public long readUpdate(Map<String, Object> update) throws IOException, FormatErrorException {
        while (true) {
            Object msg = readMessage();
            if (msg instanceof MessageData) {
                applyMsg(update, (MessageData) msg);
                return ((MessageData) msg).timestamp;
            }
        }
    }

    @Override
    public Map<String, String> getFields() {
        return fieldsList;
    }

    /**
     * Read next message from log
     *
     * @return log message
     * @throws IOException  on IO error
     * @throws EOFException on end of stream
     */
    public Object readMessage() throws IOException, FormatErrorException {
        while (true) {
            fillBuffer(HDRLEN);
            long pos = position();
            int msgType = buffer.get() & 0xFF;
            int s1 = buffer.get() & 0xFF;
            int s2 = buffer.get() & 0xFF;
            int msgSize = s1 + (256 * s2);
            try {
                fillBuffer(msgSize);
            } catch (EOFException e) {
                errors.add(new FormatErrorException(pos, "Unexpected end of file"));
                throw e;
            }
            Object msg;
            if (msgType == MESSAGE_TYPE_DATA) {
                int msgID = buffer.get() & 0xFF;
                MessageFormat msgFormat = messageFormats.get(msgID);
                if (msgFormat == null) {
                    position(pos);
                    errors.add(new FormatErrorException(pos, "Unknown DATA message ID: " + msgID));
                    buffer.position(buffer.position() + msgSize - 1);
                    continue;
                } else {
                    msg = new MessageData(msgFormat, buffer);
                }
            } else if (msgType == MESSAGE_TYPE_INFO) {
                msg = new MessageInfo(buffer);
            } else if (msgType == MESSAGE_TYPE_PARAMETER) {
                msg = new MessageParameter(buffer);
            } else if (msgType == MESSAGE_TYPE_FORMAT) {
                msg = new MessageFormat(buffer);
            } else {
                buffer.position(buffer.position() + msgSize);
                errors.add(new FormatErrorException(pos, "Unknown message type: " + msgType));
                continue;
            }
            int sizeParsed = (int) (position() - pos - HDRLEN);
            if (sizeParsed != msgSize) {
                errors.add(new FormatErrorException(pos, "Message size mismatch, parsed: " + sizeParsed + ", msg size: " + msgSize));
                buffer.position(buffer.position() + msgSize - sizeParsed);
            }
            return msg;
        }
    }

    /*
    Dump each stream of message data records to a CSV file named "topic_N.csv"
    First line of each file is "timestamp,field1,field2,..."
     */
    public static void main(String[] args) throws Exception {
        ULogReader reader = null;
        JFileChooser openLogFileChooser = new JFileChooser();
        String basePath = "/home/markw/gdrive/flightlogs/logger";
        openLogFileChooser.setCurrentDirectory(new File(basePath));
        int returnVal = openLogFileChooser.showDialog(null, "Open");
        if (returnVal == JFileChooser.APPROVE_OPTION) {
            File file = openLogFileChooser.getSelectedFile();
            String logFileName = file.getPath();
            basePath = file.getParent();
            reader = new ULogReader(logFileName);
        } else {
            System.exit(0);
        }
        // write all parameters to a gnu Octave data file
        FileWriter fileWriter = new FileWriter(new File(basePath + File.separator + "parameters.text"));
        Map<String, Object> tmap = new TreeMap<String, Object>(reader.parameters);
        Set pSet = tmap.entrySet();
        for (Object aPSet : pSet) {
            Map.Entry param = (Map.Entry) aPSet;
            fileWriter.write(String.format("# name: %s\n#type: scalar\n%s\n", param.getKey(), param.getValue()));
        }
        fileWriter.close();
        long tStart = System.currentTimeMillis();
        double last_t = 0;
        double last_p = 0;
        Map<String, PrintStream> ostream = new HashMap<String, PrintStream>();
        Map<String, Double> lastTimeStamp = new HashMap<String, Double>();
        double min_dt = 1;
        while (true) {
//            try {
//                Object msg = reader.readMessage();
//                System.out.println(msg);
//            } catch (EOFException e) {
//                break;
//            }
            Map<String, Object> update = new HashMap<String, Object>();
            try {
                long t = reader.readUpdate(update);
                double tsec = (double)t / 1e6;
                if (tsec > (last_p + 1)) {
                    last_p = tsec;
                    System.out.printf("%8.0f\n", tsec);
                }
                // keys in Map "update" are fieldnames beginning with the topic name e.g. SENSOR_GYRO_0.someField
                // Create a printstream for each topic when it is first encountered
                Set<String> keySet = update.keySet();
                String stream = keySet.iterator().next().split("\\.")[0];
                if (!ostream.containsKey(stream)) {
                    System.out.println("creating stream " + stream);
                    PrintStream newStream = new PrintStream(basePath + File.separator + stream + ".csv");
                    ostream.put(stream, newStream);
                    lastTimeStamp.put(stream, tsec);
                    Iterator<String> keys = keySet.iterator();
                    newStream.print("timestamp");
                    while (keys.hasNext()) {
                        String fieldName = keys.next();
                        if (!fieldName.startsWith("_padding") && fieldName != "timestamp") {
                            newStream.print(',');
                            newStream.print(fieldName);
                        }
                    }
                    newStream.println();
                }
                // append this record to output stream
                PrintStream curStream = ostream.get(stream);
                // timestamp is always first entry in record
                curStream.print(t);
                // for each non-padding field, print value
                Iterator<String> keys = keySet.iterator();
                while (keys.hasNext()) {
                    String fieldName = keys.next();
                    if (!fieldName.startsWith("_padding") && fieldName != "timestamp") {
                        curStream.print(',');
                        curStream.print(update.get(fieldName));
                    }
                }
//                for (Object field: update.values()) {
//                    curStream.print(',');
//                    curStream.print(field.toString());
//                }
                curStream.println();
                // check gyro stream for dropouts
                if (stream.startsWith("SENSOR_GYRO")) {
                    double dt = tsec - lastTimeStamp.get(stream);
                    double rdt = Math.rint(1000*dt) / 1000;
                    if ((dt > 0) && (rdt < min_dt)) {
                        min_dt = rdt;
                        System.out.println("rdt: " + rdt);
                    }
                    if (dt > (5 * min_dt)) {
                        System.out.println("gyro dropout: " + lastTimeStamp.get(stream) + ", length: " + dt);
                    }
                    lastTimeStamp.put(stream, tsec);
                }
            } catch (EOFException e) {
                break;
            }
        }
        long tEnd = System.currentTimeMillis();
        System.out.println(tEnd - tStart);
        reader.close();
    }

    @Override
    public List<Exception> getErrors() {
        return Collections.emptyList();
    }

    @Override
    public void clearErrors() {
    }
}
