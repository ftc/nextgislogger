/*
 * *****************************************************************************
 * Project: NextGIS Logger
 * Purpose: Productive data logger for Android
 * Author:  Stanislav Petriakov, becomeglory@gmail.com
 * *****************************************************************************
 * Copyright © 2015 NextGIS
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * *****************************************************************************
 */

package com.nextgis.logger;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.text.TextUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

// http://stackoverflow.com/q/10327506
public class ArduinoEngine {
    private final static byte DELIMITER = 10;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothDevice mDevice;
    private BluetoothSocket mSocket;
    private OutputStream mOutputStream;
    private InputStream mInputStream;

    private byte[] mBuffer;
    private int mBufferPosition;

    private Thread mWorkerThread;
    private volatile boolean mIsWorking;
    private boolean mIsLoggerServiceRunning = false;

    String data = "No data";

    private volatile int mTemperature, mHumidity, mNoise;
    private volatile double mCO, mCH4, mC4H10;

    private List<ArduinoInfoListener> mArduinoListeners;

    interface ArduinoInfoListener {
        public void onArduinoInfoChanged();
    }

    public void addArduinoListener(ArduinoInfoListener listener) {
        mArduinoListeners.add(listener);
    }

    public boolean removeArduinoListener(ArduinoInfoListener listener) {
        boolean result = mArduinoListeners.remove(listener);

        if (mArduinoListeners.size() == 0 && !mIsLoggerServiceRunning)
            closeConnection();

        return result;
    }

    private void notifyArduinoListeners() {
        for (ArduinoInfoListener listener : mArduinoListeners)
            listener.onArduinoInfoChanged();
    }

    public ArduinoEngine() {
        mArduinoListeners = new ArrayList<>();
    }

    public void setLoggerServiceRunning(boolean serviceStatus) {
        mIsLoggerServiceRunning = serviceStatus;
    }

    public void onPause() {
        if (mIsLoggerServiceRunning)
            return;

        closeConnection();
    }

    public void onResume() {
        if (mIsLoggerServiceRunning && mWorkerThread != null && mWorkerThread.isAlive())
            return;

        openConnection();

        mIsWorking = false;
        mBufferPosition = 0;
        mBuffer = new byte[1024];
        mWorkerThread = new Thread(new Runnable() {
            public void run() {
                while (!mIsWorking) {
                    try {
                        if (!isAvailable() || !isConnected())
                            openConnection();

                        int bytesAvailable = mInputStream.available();
                        if (bytesAvailable > 0) {
                            byte[] packetBytes = new byte[bytesAvailable];
                            int readLength = mInputStream.read(packetBytes);

                            for (int i = 0; i < readLength; i++) {
                                byte b = packetBytes[i];

                                if (b == DELIMITER) {
                                    byte[] encodedBytes = new byte[mBufferPosition];
                                    System.arraycopy(mBuffer, 0, encodedBytes, 0, encodedBytes.length);
                                    data = new String(encodedBytes, "US-ASCII");
                                    parseData();
                                    mBufferPosition = 0;
                                } else {
                                    mBuffer[mBufferPosition++] = b;
                                }
                            }

                            notifyArduinoListeners();
                        }
                    } catch (IOException | NullPointerException ignored) { }
                }

                closeConnection();
            }
        });

        mWorkerThread.start();
    }

    private void parseData() {
        String[] fields = data.split(";");

        if (!TextUtils.isEmpty(data) && fields.length == 6) {
            mTemperature = Integer.parseInt(fields[0]);
            mHumidity = Integer.parseInt(fields[1]);
            mNoise = Integer.parseInt((fields[2]));
            mCO = Double.parseDouble(fields[3]);
            mC4H10 = Double.parseDouble(fields[4]);
            mCH4 = Double.parseDouble(fields[5]);
        }
    }

    private boolean openConnection() {
        try {
            findBT();
            openBT();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
        }

        return false;
    }

    public String getData() {
        return data;
    }

    public int getTemperature() {
        return mTemperature;
    }

    public int getHumidity() {
        return mHumidity;
    }

    public int getNoise() {
        return mNoise;
    }

    public double getCO() {
        return mCO;
    }

    public double getC4H10() {
        return mC4H10;
    }

    public double getCH4() {
        return mCH4;
    }

    private void findBT() throws IOException {
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            throw new IOException("Bluetooth adapter is not available");
        }

        Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                if (device.getName().equals("HC-06")) {
                    mDevice = device;
                    return;
                }
            }
        }

        throw new IOException("Can not find Arduino's bluetooth");
    }

    public boolean isAvailable() {
        return (mBluetoothAdapter != null && mBluetoothAdapter.isEnabled() && mDevice != null);
    }

    private void openBT() throws IOException {
        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"); //Standard //SerialPortService ID
        mSocket = mDevice.createRfcommSocketToServiceRecord(uuid);
        mSocket.connect();
        mOutputStream = mSocket.getOutputStream();
        mInputStream = mSocket.getInputStream();
    }

    public boolean isConnected() {
        return (mInputStream != null && mOutputStream != null && mSocket != null);
    }

    private void closeConnection() {
        mIsWorking = true;

        if (isConnected()) {
            try {
                mSocket.close();

                if (mInputStream != null)
                    mInputStream.close();

                if (mOutputStream != null)
                    mOutputStream.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        mOutputStream = null;
        mInputStream = null;
        mSocket = null;
    }

    public String getItem(String ID, String markName, String userName, long timeStamp) {
        StringBuilder sb = new StringBuilder();

        sb.append(ID).append(C.CSV_SEPARATOR);
        sb.append(markName).append(C.CSV_SEPARATOR);
        sb.append(userName).append(C.CSV_SEPARATOR);
        sb.append(timeStamp).append(C.CSV_SEPARATOR);
        sb.append(getData());

        return sb.toString();
    }
}
