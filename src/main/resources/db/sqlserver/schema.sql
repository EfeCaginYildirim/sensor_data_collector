-- Sensor Data Collector — SQL Server schema (see db/h2/schema.sql for the embedded variant).
-- Idempotent: every statement can be re-run. Batches are separated by lines containing only GO.
-- All DATETIME2 values are UTC.

IF OBJECT_ID(N'dbo.monitor', N'U') IS NULL
CREATE TABLE dbo.monitor (
    monitor_id          INT            NOT NULL CONSTRAINT pk_monitor PRIMARY KEY,
    plant_id            INT            NOT NULL,
    area_id             INT            NOT NULL,
    area_name           NVARCHAR(255)  NULL,
    machine_group_id    INT            NOT NULL,
    machine_group_name  NVARCHAR(255)  NULL,
    machine_id          INT            NOT NULL,
    machine_name        NVARCHAR(255)  NULL,
    monitor_name        NVARCHAR(255)  NULL,
    device_identifier   NVARCHAR(255)  NULL,
    orientation_code    NVARCHAR(64)   NULL,
    updated_at          DATETIME2(3)   NOT NULL
);
GO

-- Health snapshot taken at collection time (not copied onto historical samples).
IF OBJECT_ID(N'dbo.monitor_status', N'U') IS NULL
CREATE TABLE dbo.monitor_status (
    monitor_id              INT          NOT NULL CONSTRAINT fk_monitor_status_monitor REFERENCES dbo.monitor (monitor_id),
    collected_at            DATETIME2(3) NOT NULL,
    health_score            INT          NULL,
    status                  INT          NULL,
    trend                   INT          NULL,
    alarms                  INT          NULL,
    speed                   FLOAT        NULL,
    velocity_x              FLOAT        NULL,
    velocity_x_operational  FLOAT        NULL,
    velocity_x_caution      FLOAT        NULL,
    velocity_x_warning      FLOAT        NULL,
    velocity_y              FLOAT        NULL,
    velocity_y_operational  FLOAT        NULL,
    velocity_y_caution      FLOAT        NULL,
    velocity_y_warning      FLOAT        NULL,
    velocity_z              FLOAT        NULL,
    velocity_z_operational  FLOAT        NULL,
    velocity_z_caution      FLOAT        NULL,
    velocity_z_warning      FLOAT        NULL,
    temperature             FLOAT        NULL,
    temperature_operational FLOAT        NULL,
    temperature_caution     FLOAT        NULL,
    temperature_warning     FLOAT        NULL,
    CONSTRAINT pk_monitor_status PRIMARY KEY (monitor_id, collected_at)
);
GO

-- Daily aggregates; the vendor's JSON documents are stored verbatim.
IF OBJECT_ID(N'dbo.basic_feature', N'U') IS NULL
CREATE TABLE dbo.basic_feature (
    monitor_id   INT           NOT NULL CONSTRAINT fk_basic_feature_monitor REFERENCES dbo.monitor (monitor_id),
    sample_time  DATETIME2(3)  NOT NULL,
    source_id    NVARCHAR(64)  NULL,
    json_avg     NVARCHAR(MAX) NOT NULL,
    json_min     NVARCHAR(MAX) NOT NULL,
    json_max     NVARCHAR(MAX) NOT NULL,
    CONSTRAINT pk_basic_feature PRIMARY KEY (monitor_id, sample_time)
);
GO

-- One row per computed-feature measurement. Unknown values are NULL, never 0.
IF OBJECT_ID(N'dbo.computed_feature', N'U') IS NULL
CREATE TABLE dbo.computed_feature (
    monitor_id   INT          NOT NULL CONSTRAINT fk_computed_feature_monitor REFERENCES dbo.monitor (monitor_id),
    sample_time  DATETIME2(3) NOT NULL,
    kurtacclx    FLOAT NULL,
    kurtaccly    FLOAT NULL,
    kurtacclz    FLOAT NULL,
    ppacclx      FLOAT NULL,
    ppaccly      FLOAT NULL,
    ppacclz      FLOAT NULL,
    crestacclx   FLOAT NULL,
    crestaccly   FLOAT NULL,
    crestacclz   FLOAT NULL,
    grmsacclx    FLOAT NULL,
    grmsaccly    FLOAT NULL,
    grmsacclz    FLOAT NULL,
    ppswx        FLOAT NULL,
    ppswy        FLOAT NULL,
    ppswz        FLOAT NULL,
    swrmsx       FLOAT NULL,
    swrmsy       FLOAT NULL,
    swrmsz       FLOAT NULL,
    idle         FLOAT NULL,
    rpm          FLOAT NULL,
    bpfi         FLOAT NULL,
    bpfo         FLOAT NULL,
    bsf          FLOAT NULL,
    ftf          FLOAT NULL,
    [load]       FLOAT NULL,
    vrmsx        FLOAT NULL,
    vrmsy        FLOAT NULL,
    vrmsz        FLOAT NULL,
    ta           FLOAT NULL,
    CONSTRAINT pk_computed_feature PRIMARY KEY (monitor_id, sample_time)
);
GO

-- Exclusive upper bound of the data collected without gaps, per monitor.
-- Updated in the same transaction as the samples, so it can never run ahead of the data.
IF OBJECT_ID(N'dbo.collection_watermark', N'U') IS NULL
CREATE TABLE dbo.collection_watermark (
    monitor_id      INT          NOT NULL CONSTRAINT pk_collection_watermark PRIMARY KEY
                                 CONSTRAINT fk_collection_watermark_monitor REFERENCES dbo.monitor (monitor_id),
    collected_until DATETIME2(3) NOT NULL,
    updated_at      DATETIME2(3) NOT NULL
);
GO

-- One row per collection run, for the run history shown in the UI.
IF OBJECT_ID(N'dbo.collection_run', N'U') IS NULL
CREATE TABLE dbo.collection_run (
    run_id          VARCHAR(16)    NOT NULL CONSTRAINT pk_collection_run PRIMARY KEY,
    started_at      DATETIME2(3)   NOT NULL,
    elapsed_ms      BIGINT         NOT NULL,
    status          VARCHAR(16)    NOT NULL,
    failure         NVARCHAR(2000) NULL,
    monitors_total  INT            NOT NULL,
    monitors_failed INT            NOT NULL,
    new_rows        INT            NOT NULL,
    summary         NVARCHAR(MAX)  NOT NULL
);
GO

-- Convenience view for digital-twin consumers: one row per monitor and day, joining the daily
-- aggregate with that day's computed-feature averages. Joining on the day replaces the
-- cartesian product of the legacy single-table design.
CREATE OR ALTER VIEW dbo.v_monitor_daily AS
SELECT m.monitor_id,
       m.machine_name,
       m.monitor_name,
       d.day,
       b.json_avg,
       b.json_min,
       b.json_max,
       d.samples,
       d.avg_vrmsx,
       d.avg_vrmsy,
       d.avg_vrmsz,
       d.avg_kurtacclx,
       d.avg_bpfo,
       d.avg_rpm,
       d.avg_load,
       d.avg_ta
FROM dbo.monitor AS m
JOIN (SELECT monitor_id,
             CAST(sample_time AS DATE) AS day,
             COUNT(*)       AS samples,
             AVG(vrmsx)     AS avg_vrmsx,
             AVG(vrmsy)     AS avg_vrmsy,
             AVG(vrmsz)     AS avg_vrmsz,
             AVG(kurtacclx) AS avg_kurtacclx,
             AVG(bpfo)      AS avg_bpfo,
             AVG(rpm)       AS avg_rpm,
             AVG([load])    AS avg_load,
             AVG(ta)        AS avg_ta
      FROM dbo.computed_feature
      GROUP BY monitor_id, CAST(sample_time AS DATE)) AS d
  ON d.monitor_id = m.monitor_id
LEFT JOIN dbo.basic_feature AS b
  ON b.monitor_id = d.monitor_id
 AND b.sample_time >= CAST(d.day AS DATETIME2(3))
 AND b.sample_time < DATEADD(DAY, 1, CAST(d.day AS DATETIME2(3)));
GO
