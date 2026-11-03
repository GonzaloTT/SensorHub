package com.gonzalo.sensorhub.data;
import androidx.room.*;
@Database(entities={Measurement.class},version=1,exportSchema=false)
public abstract class HubDatabase extends RoomDatabase {
    public abstract Measurements measurements();
}
