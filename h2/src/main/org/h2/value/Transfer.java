/*
 * Copyright 2004-2011 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.value;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.Socket;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import org.h2.constant.ErrorCode;
import org.h2.engine.Constants;
import org.h2.engine.SessionInterface;
import org.h2.message.DbException;
import org.h2.message.TraceSystem;
import org.h2.store.Data;
import org.h2.store.DataReader;
import org.h2.tools.SimpleResultSet;
import org.h2.util.DateTimeUtils;
import org.h2.util.IOUtils;
import org.h2.util.NetUtils;
import org.h2.util.StringUtils;
import org.h2.util.Utils;

/**
 * The transfer class is used to send and receive Value objects.
 * It is used on both the client side, and on the server side.
 */
public class Transfer {

    private static final int BUFFER_SIZE = 16 * 1024;
    private static final int LOB_MAGIC = 0x1234;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private SessionInterface session;
    private boolean ssl;
    private int version;

    /**
     * Create a new transfer object for the specified session.
     *
     * @param session the session
     */
    public Transfer(SessionInterface session) {
        this.session = session;
    }

    /**
     * Set the socket this object uses.
     *
     * @param s the socket
     */
    public void setSocket(Socket s) {
        socket = s;
    }

    /**
     * Initialize the transfer object. This method will try to open an input and
     * output stream.
     */
    public void init() throws IOException {
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), Transfer.BUFFER_SIZE));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), Transfer.BUFFER_SIZE));
    }

    /**
     * Write pending changes.
     */
    public void flush() throws IOException {
        out.flush();
    }

    /**
     * Write a boolean.
     *
     * @param x the value
     * @return itself
     */
    public Transfer writeBoolean(boolean x) throws IOException {
        out.writeByte((byte) (x ? 1 : 0));
        return this;
    }

    /**
     * Read a boolean.
     *
     * @return the value
     */
    public boolean readBoolean() throws IOException {
        return in.readByte() == 1;
    }

    /**
     * Write a byte.
     *
     * @param x the value
     * @return itself
     */
    private Transfer writeByte(byte x) throws IOException {
        out.writeByte(x);
        return this;
    }

    /**
     * Read a byte.
     *
     * @return the value
     */
    private byte readByte() throws IOException {
        return in.readByte();
    }

    /**
     * Write an int.
     *
     * @param x the value
     * @return itself
     */
    public Transfer writeInt(int x) throws IOException {
        out.writeInt(x);
        return this;
    }

    /**
     * Read an int.
     *
     * @return the value
     */
    public int readInt() throws IOException {
        return in.readInt();
    }

    /**
     * Write a long.
     *
     * @param x the value
     * @return itself
     */
    public Transfer writeLong(long x) throws IOException {
        out.writeLong(x);
        return this;
    }

    /**
     * Read a long.
     *
     * @return the value
     */
    public long readLong() throws IOException {
        return in.readLong();
    }

    /**
     * Write a double.
     *
     * @param i the value
     * @return itself
     */
    private Transfer writeDouble(double i) throws IOException {
        out.writeDouble(i);
        return this;
    }

    /**
     * Write a float.
     *
     * @param i the value
     * @return itself
     */
    private Transfer writeFloat(float i) throws IOException {
        out.writeFloat(i);
        return this;
    }

    /**
     * Read a double.
     *
     * @return the value
     */
    private double readDouble() throws IOException {
        return in.readDouble();
    }

    /**
     * Read a float.
     *
     * @return the value
     */
    private float readFloat() throws IOException {
        return in.readFloat();
    }

    /**
     * Write a string. The maximum string length is Integer.MAX_VALUE.
     *
     * @param s the value
     * @return itself
     */
    public Transfer writeString(String s) throws IOException {
        if (s == null) {
            out.writeInt(-1);
        } else {
            int len = s.length();
            out.writeInt(len);
            for (int i = 0; i < len; i++) {
                out.writeChar(s.charAt(i));
            }
        }
        return this;
    }

    /**
     * Read a string.
     *
     * @return the value
     */
    public String readString() throws IOException {
        int len = in.readInt();
        if (len == -1) {
            return null;
        }
        StringBuilder buff = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            buff.append(in.readChar());
        }
        String s = buff.toString();
        s = StringUtils.cache(s);
        return s;
    }

    /**
     * Write a byte array.
     *
     * @param data the value
     * @return itself
     */
    public Transfer writeBytes(byte[] data) throws IOException {
        if (data == null) {
            writeInt(-1);
        } else {
            writeInt(data.length);
            out.write(data);
        }
        return this;
    }

    /**
     * Read a byte array.
     *
     * @return the value
     */
    public byte[] readBytes() throws IOException {
        int len = readInt();
        if (len == -1) {
            return null;
        }
        byte[] b = Utils.newBytes(len);
        in.readFully(b);
        return b;
    }

    /**
     * Close the transfer object and the socket.
     */
    public void close() {
        if (socket != null) {
            try {
                out.flush();
                if (socket != null) {
                    socket.close();
                }
            } catch (IOException e) {
                TraceSystem.traceThrowable(e);
            } finally {
                socket = null;
            }
        }
    }

    /**
     * Write a value.
     *
     * @param v the value
     */
    public void writeValue(Value v) throws IOException {
        int type = v.getType();
        writeInt(type);
        switch (type) {
        case Value.NULL:
            break;
        case Value.BYTES:
        case Value.JAVA_OBJECT:
            writeBytes(v.getBytesNoCopy());
            break;
        case Value.UUID: {
            ValueUuid uuid = (ValueUuid) v;
            writeLong(uuid.getHigh());
            writeLong(uuid.getLow());
            break;
        }
        case Value.BOOLEAN:
            writeBoolean(v.getBoolean().booleanValue());
            break;
        case Value.BYTE:
            writeByte(v.getByte());
            break;
        case Value.TIME:
            if (version >= Constants.TCP_PROTOCOL_VERSION_9) {
                writeLong(DateTimeUtils.getTimeLocal(v.getTimeNoCopy()));
            } else if (version >= Constants.TCP_PROTOCOL_VERSION_7) {
                writeLong(DateTimeUtils.getTimeLocalWithoutDst(v.getTimeNoCopy()));
            } else {
                writeLong(v.getTimeNoCopy().getTime());
            }
            break;
        case Value.DATE:
            if (version >= Constants.TCP_PROTOCOL_VERSION_9) {
                writeLong(DateTimeUtils.getTimeLocal(v.getDateNoCopy()));
            } else if (version >= Constants.TCP_PROTOCOL_VERSION_7) {
                writeLong(DateTimeUtils.getTimeLocalWithoutDst(v.getDateNoCopy()));
            } else {
                writeLong(v.getDateNoCopy().getTime());
            }
            break;
        case Value.TIMESTAMP: {
            Timestamp ts = v.getTimestampNoCopy();
            if (version >= Constants.TCP_PROTOCOL_VERSION_9) {
                writeLong(DateTimeUtils.getTimeLocal(ts));
            } else if (version >= Constants.TCP_PROTOCOL_VERSION_7) {
                writeLong(DateTimeUtils.getTimeLocalWithoutDst(ts));
            } else {
                writeLong(ts.getTime());
            }
            writeInt(ts.getNanos());
            break;
        }
        case Value.DECIMAL:
            writeString(v.getString());
            break;
        case Value.DOUBLE:
            writeDouble(v.getDouble());
            break;
        case Value.FLOAT:
            writeFloat(v.getFloat());
            break;
        case Value.INT:
            writeInt(v.getInt());
            break;
        case Value.LONG:
            writeLong(v.getLong());
            break;
        case Value.SHORT:
            writeInt(v.getShort());
            break;
        case Value.STRING:
        case Value.STRING_IGNORECASE:
        case Value.STRING_FIXED:
            writeString(v.getString());
            break;
        case Value.BLOB: {
            long length = v.getPrecision();
            if (length < 0) {
                throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, "length=" + length);
            }
            writeLong(length);
            long written = IOUtils.copyAndCloseInput(v.getInputStream(), out);
            if (written != length) {
                throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, "length:" + length + " written:" + written);
            }
            writeInt(LOB_MAGIC);
            break;
        }
        case Value.CLOB: {
            long length = v.getPrecision();
            if (length < 0) {
                throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, "length=" + length);
            }
            writeLong(length);
            Reader reader = v.getReader();
            Data.copyString(reader, out);
            writeInt(LOB_MAGIC);
            break;
        }
        case Value.ARRAY: {
            ValueArray va = (ValueArray) v;
            Value[] list = va.getList();
            int len = list.length;
            Class<?> componentType = va.getComponentType();
            if (componentType == Object.class) {
                writeInt(len);
            } else {
                writeInt(-(len + 1));
                writeString(componentType.getName());
            }
            for (Value value : list) {
                writeValue(value);
            }
            break;
        }
        case Value.RESULT_SET: {
            try {
                ResultSet rs = ((ValueResultSet) v).getResultSet();
                rs.beforeFirst();
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                writeInt(columnCount);
                for (int i = 0; i < columnCount; i++) {
                    writeString(meta.getColumnName(i + 1));
                    writeInt(meta.getColumnType(i + 1));
                    writeInt(meta.getPrecision(i + 1));
                    writeInt(meta.getScale(i + 1));
                }
                while (rs.next()) {
                    writeBoolean(true);
                    for (int i = 0; i < columnCount; i++) {
                        int t = DataType.convertSQLTypeToValueType(meta.getColumnType(i + 1));
                        Value val = DataType.readValue(session, rs, i + 1, t);
                        writeValue(val);
                    }
                }
                writeBoolean(false);
                rs.beforeFirst();
            } catch (SQLException e) {
                throw DbException.convertToIOException(e);
            }
            break;
        }
        default:
            throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, "type=" + type);
        }
    }

    /**
     * Read a value.
     *
     * @return the value
     */
    public Value readValue() throws IOException {
        int type = readInt();
        switch(type) {
        case Value.NULL:
            return ValueNull.INSTANCE;
        case Value.BYTES:
            return ValueBytes.getNoCopy(readBytes());
        case Value.UUID:
            return ValueUuid.get(readLong(), readLong());
        case Value.JAVA_OBJECT:
            return ValueJavaObject.getNoCopy(readBytes());
        case Value.BOOLEAN:
            return ValueBoolean.get(readBoolean());
        case Value.BYTE:
            return ValueByte.get(readByte());
        case Value.DATE:
            if (version >= Constants.TCP_PROTOCOL_VERSION_9) {
                return ValueDate.getNoCopy(new Date(DateTimeUtils.getTimeGMT(readLong())));
            } else if (version >= Constants.TCP_PROTOCOL_VERSION_7) {
                return ValueDate.getNoCopy(new Date(DateTimeUtils.getTimeGMTWithoutDst(readLong())));
            }
            return ValueDate.getNoCopy(new Date(readLong()));
        case Value.TIME:
            if (version >= Constants.TCP_PROTOCOL_VERSION_9) {
                return ValueTime.getNoCopy(new Time(DateTimeUtils.getTimeGMT(readLong())));
            } else if (version >= Constants.TCP_PROTOCOL_VERSION_7) {
                return ValueTime.getNoCopy(new Time(DateTimeUtils.getTimeGMTWithoutDst(readLong())));
            }
            return ValueTime.getNoCopy(new Time(readLong()));
        case Value.TIMESTAMP: {
            if (version >= Constants.TCP_PROTOCOL_VERSION_9) {
                Timestamp ts = new Timestamp(DateTimeUtils.getTimeGMT(readLong()));
                ts.setNanos(readInt());
                return ValueTimestamp.getNoCopy(ts);
            } else if (version >= Constants.TCP_PROTOCOL_VERSION_7) {
                Timestamp ts = new Timestamp(DateTimeUtils.getTimeGMTWithoutDst(readLong()));
                ts.setNanos(readInt());
                return ValueTimestamp.getNoCopy(ts);
            }
            Timestamp ts = new Timestamp(readLong());
            ts.setNanos(readInt());
            return ValueTimestamp.getNoCopy(ts);
        }
        case Value.DECIMAL:
            return ValueDecimal.get(new BigDecimal(readString()));
        case Value.DOUBLE:
            return ValueDouble.get(readDouble());
        case Value.FLOAT:
            return ValueFloat.get(readFloat());
        case Value.INT:
            return ValueInt.get(readInt());
        case Value.LONG:
            return ValueLong.get(readLong());
        case Value.SHORT:
            return ValueShort.get((short) readInt());
        case Value.STRING:
            return ValueString.get(readString());
        case Value.STRING_IGNORECASE:
            return ValueStringIgnoreCase.get(readString());
        case Value.STRING_FIXED:
            return ValueStringFixed.get(readString());
        case Value.BLOB: {
            long length = readLong();
            Value v = session.getDataHandler().getLobStorage().createBlob(in, length);
            int magic = readInt();
            if (magic != LOB_MAGIC) {
                throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, "magic=" + magic);
            }
            return v;
        }
        case Value.CLOB: {
            long length = readLong();
            Value v = session.getDataHandler().getLobStorage().createClob(new DataReader(in), length);
            int magic = readInt();
            if (magic != LOB_MAGIC) {
                throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, "magic=" + magic);
            }
            return v;
        }
        case Value.ARRAY: {
            int len = readInt();
            Class<?> componentType = Object.class;
            if (len < 0) {
                len = -(len + 1);
                componentType = Utils.loadUserClass(readString());
            }
            Value[] list = new Value[len];
            for (int i = 0; i < len; i++) {
                list[i] = readValue();
            }
            return ValueArray.get(componentType, list);
        }
        case Value.RESULT_SET: {
            SimpleResultSet rs = new SimpleResultSet();
            int columns = readInt();
            for (int i = 0; i < columns; i++) {
                rs.addColumn(readString(), readInt(), readInt(), readInt());
            }
            while (true) {
                if (!readBoolean()) {
                    break;
                }
                Object[] o = new Object[columns];
                for (int i = 0; i < columns; i++) {
                    o[i] = readValue().getObject();
                }
                rs.addRow(o);
            }
            return ValueResultSet.get(rs);
        }
        default:
            throw DbException.get(ErrorCode.CONNECTION_BROKEN_1, "type=" + type);
        }
    }

    /**
     * Get the socket.
     *
     * @return the socket
     */
    public Socket getSocket() {
        return socket;
    }

    /**
     * Set the session.
     *
     * @param session the session
     */
    public void setSession(SessionInterface session) {
        this.session = session;
    }

    /**
     * Enable or disable SSL.
     *
     * @param ssl the new value
     */
    public void setSSL(boolean ssl) {
        this.ssl = ssl;
    }

    /**
     * Open a new new connection to the same address and port as this one.
     *
     * @return the new transfer object
     */
    public Transfer openNewConnection() throws IOException {
        InetAddress address = socket.getInetAddress();
        int port = socket.getPort();
        Socket s2 = NetUtils.createSocket(address, port, ssl);
        Transfer trans = new Transfer(null);
        trans.setSocket(s2);
        trans.setSSL(ssl);
        return trans;
    }

    public void setVersion(int version) {
        this.version = version;
    }

}