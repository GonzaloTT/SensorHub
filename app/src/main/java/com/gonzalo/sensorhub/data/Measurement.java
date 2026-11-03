package com.gonzalo.sensorhub.data;
import androidx.annotation.NonNull;
import androidx.room.*;
@Entity(indices={@Index("fechaHora"), @Index("variable"), @Index("sincronizado")})
public class Measurement {
    @PrimaryKey @NonNull public String medicionId = "";
    @NonNull public String equipoId = "EQUIPO_01";
    public String usuarioId;
    @NonNull public String dispositivoId = "";
    @NonNull public String origen = "";
    @NonNull public String variable = "";
    public double valor;
    @NonNull public String unidad = "";
    @NonNull public String fechaHora = "";
    public boolean sincronizado;
}
