package com.gonzalo.sensorhub.data;
import androidx.room.*;
import java.util.List;
import kotlinx.coroutines.flow.Flow;
@Dao
public interface Measurements {
    @Insert void insert(List<Measurement> rows);
    @Query("SELECT * FROM Measurement WHERE (:source IS NULL OR origen=:source) AND (:metric IS NULL OR variable=:metric) ORDER BY fechaHora DESC, variable")
    Flow<List<Measurement>> history(String source, String metric);
    @Query("SELECT * FROM Measurement WHERE medicionId IN (SELECT (SELECT medicionId FROM Measurement m WHERE m.variable=v.variable ORDER BY fechaHora DESC, medicionId DESC LIMIT 1) FROM (SELECT DISTINCT variable FROM Measurement) v) ORDER BY variable")
    Flow<List<Measurement>> latest();
    @Query("SELECT COUNT(*) FROM Measurement WHERE sincronizado=:sent") Flow<Integer> count(boolean sent);
    @Query("UPDATE Measurement SET usuarioId=:uid WHERE usuarioId IS NULL") void claim(String uid);
    @Query("SELECT COALESCE(MAX(rowid),0) FROM Measurement") long watermark();
    @Query("SELECT COUNT(*) FROM Measurement WHERE sincronizado=0 AND usuarioId!=:uid") int otherOwners(String uid);
    @Query("SELECT * FROM Measurement WHERE usuarioId=:uid AND sincronizado=0 AND rowid<=:limit ORDER BY rowid LIMIT 100") List<Measurement> batch(String uid,long limit);
    @Query("SELECT * FROM Measurement WHERE usuarioId=:uid AND sincronizado=0 ORDER BY fechaHora LIMIT 100") List<Measurement> pending(String uid);
    @Query("UPDATE Measurement SET sincronizado=1 WHERE medicionId=:id AND usuarioId=:uid") void confirm(String id,String uid);
}
