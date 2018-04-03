package com.example.pc.accelerometer;

/**
 * Created by calista on 26/03/2018.
 */

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.widget.TextView;
import android.app.Service;


public class Gabungan extends Activity implements SensorEventListener{

    private final long inactivityWindow = 3000;
    private boolean buffering = false;
    private final float g = 9.80665f;
    private final float inactivity_up = 1.1f * g; // 1.1
    private final float inactivity_low = 0.9f * g; // 0.9
    private final float t_deviation = 0.1f * g;
    private final float fall_min = 2.1f * g;
    private final float fall_max = 0.7f * g;
    private float n;
    private final long totalWindow = 6000;

    private SensorManager SM;
    private Sensor mySensor;

    private SensorManager sensorManager;
    private Sensor sensor;

    final RingBufferFloat readings = new RingBufferFloat(250);
    final RingBufferLong tReadings = new RingBufferLong(250);
    final RingBufferFloat backBuffer = new RingBufferFloat(500);
    final RingBufferLong tBackBuffer = new RingBufferLong(500);


    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

}

    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Create our Sensor Manager
        SM = (SensorManager)getSystemService(SENSOR_SERVICE);


        // Accelerometer Sensor
        mySensor = SM.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        // Register sensor Listener
        SM.registerListener(this, mySensor, SensorManager.SENSOR_DELAY_NORMAL);

        // Assign TextView
        //xText = (TextView)findViewById(R.id.xText);
        //yText = (TextView)findViewById(R.id.yText);
        //zText = (TextView)findViewById(R.id.zText);
    }

    public void onSensorChanged(SensorEvent event) {
        //calculate |n|
        n = (float) Math.sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]);
        long t = event.timestamp / 1000000l;
        //check if first buffer is full i.e. first reading is older than 3s
        if (!readings.isEmpty()
                && t - tReadings.getFirst() > inactivityWindow) {
            //add oldest reading to backBuffer
            float f = readings.dequeue();
            addToBackBuffer(f, tReadings.dequeue());
            buffering = true;
        }
        readings.add(n);
        tReadings.add(t);
        float avg = readings.getAverage();
        //average activity is low
        if (buffering && avg < inactivity_up && avg > inactivity_low) {
            //calculate standard deviation
            float varianceSum = 0;
            for(int i = 0;i<readings.size();i++){
                float i_n = readings.get(i);
                varianceSum += (i_n-avg)*(i_n-avg);
            }
            float deviation = (float) Math.sqrt(varianceSum / readings.size()-1);
            //we have a 3s reading and the average is within low activity limits
            if (deviation < t_deviation) {
                //low activity, check previous window for acceleration values beyond thresholds
                boolean possFall = false;
                float magMax = backBuffer.getMax();
                float magMin = backBuffer.getMin();
                avg = backBuffer.getAverage();
                if (magMax > fall_min && magMin < fall_max) {
                    possFall = true;
                }
                if (possFall) {
                    Log.i("FDService", "possible fall detected, checking with classifier");
                    checkFall(magMin, magMax, avg);
                }
                //if fall, we can clear the buffer, if not there was only inactivity in all readings and we
                //can start anew
                readings.clear();
                tReadings.clear();
                backBuffer.clear();
                tBackBuffer.clear();
                buffering = false;
            }
        }
    }

    private void checkFall(float min, float max, float average) {
        int[] peakCounts = new int[7];
        int gCrossings = 0;
        int avgCrossings = 0;
        float varSum = (backBuffer.getFirst() - average)*(backBuffer.getFirst()-average);
        //count peaks and 1g+avg crossings in this loop
        for (int i = 1; i < backBuffer.size() - 1; i++) {
            float l1 = backBuffer.get(i - 1);
            float n = backBuffer.get(i);
            float h1 = backBuffer.get(i + 1);
            varSum += (n - average) * (n - average); //kal: varsum= varsum + ((n-average acceleration value) * (n- acerage acc value))
            if (l1 > 1f * g && h1 < 1f * g) {
                gCrossings++;
            }
            if (l1 < 1f * g && h1 > 1f * g) {
                gCrossings++;
            }
            if ((l1 > average && h1 < average) || (l1 < average && h1 > average)) {
                avgCrossings++;
            }
            boolean posPeak, negPeak;
            posPeak = (l1 < n && n > h1); // local maximum
            negPeak = (l1 > n && n < h1); // local minimum
            if (!posPeak && !negPeak)
                continue;
            if (posPeak) {
                if (n <= 1.8f * g) continue;
                if (n < 2.0f * g) {
                    peakCounts[0]++;
                    continue;
                }
                if (n < 2.6f * g) {
                    continue;
                }
                if (n < 2.8f * g) {
                    peakCounts[1]++;
                    continue;
                }
                if (n < 3.0f * g) {
                    continue;
                }
                if (n < 3.2f * g) {
                    peakCounts[2]++;
                    continue;
                }
            }
            if (negPeak) {
                if (n >= g) continue;
                if (n < 0.2f * g) {
                    peakCounts[3]++;
                    continue;
                }
                if (n < 0.4f * g) {
                    peakCounts[4]++;
                    continue;
                }
                if (n < 0.6f * g) {
                    peakCounts[5]++;
                    continue;
                }
                if (n < 0.8f * g) {
                    peakCounts[6]++;
                }
            }
        }

        varSum += Math.pow(backBuffer.get(backBuffer.size() - 1) - average, 2);
        float variance = varSum / backBuffer.size();
        Float[] features = new Float[12];
        for (int i = 0; i < peakCounts.length; i++) {
            features[i] = (float) peakCounts[i];
        }
        features[7] = (float) gCrossings;
        features[8] = (float) avgCrossings;
        features[9] = variance;
        features[10] = max;
        features[11] = (max - min);
        //check feature vector with classsifier
        if (WekaClassifier.classify(features) != 0) {
            Log.i("FDService", "not classified as fall");
            return; // no fall
        }
        Log.i("FDService", "Fall detected");
        //FALL! start alert and stop this service
        /**
         *
         * Intent alertIntent = new Intent(this, AlertActivity.class);
        alertIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        this.startActivity(alertIntent);
        stopSelf();


         */
    }

    private void addToBackBuffer(float n, long t) {
        if (!backBuffer.isEmpty()
                && t - tBackBuffer.getFirst() > totalWindow) {
            //remove reading from buffer if it's too old
            backBuffer.dequeue();
            tBackBuffer.dequeue();
        }
        backBuffer.add(n);
        tBackBuffer.add(t);
    }




}