-- Sensor Data Collector — H2 schema for the embedded / desktop store.
-- Same tables and columns as db/sqlserver/schema.sql, in H2 syntax. H2 runs in MSSQLServer
-- compatibility mode, so the data statements (INSERT, DELETE, SELECT with [quoted] names and
-- OFFSET/FETCH) are shared between both databases; only DDL and upserts differ (see SqlDialect).
-- All TIMESTAMP values are UTC. Batches are separated by lines containing only GO.

CREATE SCHEMA IF NOT EXISTS dbo;
GO

CREATE TABLE IF NOT EXISTS dbo.monitor (
    monitor_id          INT          NOT NULL PRIMARY KEY,
    plant_id            INT          NOT NULL,
    area_id             INT          NOT NULL,
    area_name           VARCHAR(255),
    machine_group_id    INT          NOT NULL,
    machine_group_name  VARCHAR(255),
    machine_id          INT          NOT NULL,
    machine_name        VARCHAR(255),
    monitor_name        VARCHAR(255),
    device_identifier   VARCHAR(255),
    orientation_code    VARCHAR(64),
    updated_at          TIMESTAMP(3) NOT NULL
);
GO

CREATE TABLE IF NOT EXISTS dbo.monitor_status (
    monitor_id              INT          NOT NULL REFERENCES dbo.monitor (monitor_id),
    collected_at            TIMESTAMP(3) NOT NULL,
    health_score            INT,
    status                  INT,
    trend                   INT,
    alarms                  INT,
    speed                   DOUBLE PRECISION,
    velocity_x              DOUBLE PRECISION,
    velocity_x_operational  DOUBLE PRECISION,
    velocity_x_caution      DOUBLE PRECISION,
    velocity_x_warning      DOUBLE PRECISION,
    velocity_y              DOUBLE PRECISION,
    velocity_y_operational  DOUBLE PRECISION,
    velocity_y_caution      DOUBLE PRECISION,
    velocity_y_warning      DOUBLE PRECISION,
    velocity_z              DOUBLE PRECISION,
    velocity_z_operational  DOUBLE PRECISION,
    velocity_z_caution      DOUBLE PRECISION,
    velocity_z_warning      DOUBLE PRECISION,
    temperature             DOUBLE PRECISION,
    temperature_operational DOUBLE PRECISION,
    temperature_caution     DOUBLE PRECISION,
    temperature_warning     DOUBLE PRECISION,
    PRIMARY KEY (monitor_id, collected_at)
);
GO

CREATE TABLE IF NOT EXISTS dbo.basic_feature (
    monitor_id   INT                    NOT NULL REFERENCES dbo.monitor (monitor_id),
    sample_time  TIMESTAMP(3)           NOT NULL,
    source_id    VARCHAR(64),
    json_avg     CHARACTER LARGE OBJECT NOT NULL,
    json_min     CHARACTER LARGE OBJECT NOT NULL,
    json_max     CHARACTER LARGE OBJECT NOT NULL,
    PRIMARY KEY (monitor_id, sample_time)
);
GO

CREATE TABLE IF NOT EXISTS dbo.computed_feature (
    monitor_id   INT          NOT NULL REFERENCES dbo.monitor (monitor_id),
    sample_time  TIMESTAMP(3) NOT NULL,
    kurtacclx    DOUBLE PRECISION,
    kurtaccly    DOUBLE PRECISION,
    kurtacclz    DOUBLE PRECISION,
    ppacclx      DOUBLE PRECISION,
    ppaccly      DOUBLE PRECISION,
    ppacclz      DOUBLE PRECISION,
    crestacclx   DOUBLE PRECISION,
    crestaccly   DOUBLE PRECISION,
    crestacclz   DOUBLE PRECISION,
    grmsacclx    DOUBLE PRECISION,
    grmsaccly    DOUBLE PRECISION,
    grmsacclz    DOUBLE PRECISION,
    ppswx        DOUBLE PRECISION,
    ppswy        DOUBLE PRECISION,
    ppswz        DOUBLE PRECISION,
    swrmsx       DOUBLE PRECISION,
    swrmsy       DOUBLE PRECISION,
    swrmsz       DOUBLE PRECISION,
    idle         DOUBLE PRECISION,
    rpm          DOUBLE PRECISION,
    bpfi         DOUBLE PRECISION,
    bpfo         DOUBLE PRECISION,
    bsf          DOUBLE PRECISION,
    ftf          DOUBLE PRECISION,
    [load]       DOUBLE PRECISION,
    vrmsx        DOUBLE PRECISION,
    vrmsy        DOUBLE PRECISION,
    vrmsz        DOUBLE PRECISION,
    ta           DOUBLE PRECISION,
    PRIMARY KEY (monitor_id, sample_time)
);
GO

CREATE TABLE IF NOT EXISTS dbo.collection_watermark (
    monitor_id      INT          NOT NULL PRIMARY KEY REFERENCES dbo.monitor (monitor_id),
    collected_until TIMESTAMP(3) NOT NULL,
    updated_at      TIMESTAMP(3) NOT NULL
);
GO

CREATE TABLE IF NOT EXISTS dbo.collection_run (
    run_id          VARCHAR(16)            NOT NULL PRIMARY KEY,
    started_at      TIMESTAMP(3)           NOT NULL,
    elapsed_ms      BIGINT                 NOT NULL,
    status          VARCHAR(16)            NOT NULL,
    failure         VARCHAR(2000),
    monitors_total  INT                    NOT NULL,
    monitors_failed INT                    NOT NULL,
    new_rows        INT                    NOT NULL,
    summary         CHARACTER LARGE OBJECT NOT NULL
);
GO
