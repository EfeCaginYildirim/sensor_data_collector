# IoT Sensor Data Collector

A scheduled Java application that collects vibration and health-monitoring data from industrial motors through an external API and stores it in a SQL Server database.

Built as an internship project to automate periodic data collection for predictive maintenance workflows.

---

## Features

- 🕐 **Scheduled collection** — runs at a configurable time each day (or at any interval)
- ⚡ **Parallel data fetching** — concurrent HTTP requests for multiple monitors via `ConcurrentDataCollector`
- 🗄️ **SQL Server integration** — bulk `MERGE` inserts with duplicate detection via `OptimizedDataInsertion`
- 📄 **JSON output files** — timestamped combined data snapshots saved to disk
- 🔄 **Smart date ranges** — tracks last run time; fetches only new data on subsequent runs
- ✅ **Timestamp validation** — rejects future/stale records before insertion
- 🔧 **External configuration** — all credentials and IDs live in a `.properties` file, never hardcoded

---

## Architecture

```
ScheduledDataCollector       ← entry point, scheduler, orchestrator
├── ConfigManager            ← reads scheduler_config.properties
├── ConcurrentDataCollector  ← parallel API calls per monitor
│   ├── GetPlantInfo         ← /plants/{id}
│   ├── GetTrendHistory      ← /trend-history
│   └── GetTrendComputedFeatures ← /trend/computed-features
├── HttpConnectionPool       ← pooled HTTP connections
├── JsonParserOptimized      ← fast JSON → domain model
├── DataCleaner              ← sanitizes/validates field values
├── TimestampValidator       ← rejects out-of-range timestamps
├── OptimizedDataInsertion   ← batch MERGE into SQL Server
├── DatabaseConnectionPool   ← pooled JDBC connections
├── LastRunTracker           ← persists last-run timestamp
└── PerformanceOptimizer     ← runtime timing/metrics
```

---

## Prerequisites

| Requirement | Notes |
|---|---|
| Java 11+ | Tested on Java 17 |
| SQL Server | Any supported edition; JDBC driver is resolved by Maven |
| External API access | Requires credentials supplied by your API provider |
| Jackson 2.15.2 | Included in `libs/` |

---

## Setup

### 1. Clone and configure

```bash
git clone <your-repo-url>
cd <project-dir>

# Copy the template config and fill in your values
cp scheduler_config.properties.example scheduler_config.properties
```

Edit `scheduler_config.properties`:

```properties
# Database
db_url=jdbc:sqlserver://your-server:1433;databaseName=YourDB;encrypt=true;trustServerCertificate=true;
db_user=your_db_user
db_password=your_db_password

# External API
api_base_url=https://api.example.com/v1
api_username=your@email.com
api_password=your_api_password

# Your plant and monitor IDs (obtain these from your API provider)
plant_id=12345
monitor_ids=11111,22222,33333
```

### 2. Build

```bash
mvn clean package
```

### 3. Run

```bash
java -jar target/iot-sensor-data-collector-1.0.0.jar
```

The app starts its internal scheduler and also accepts interactive commands:

| Command | Description |
|---|---|
| `run` | Trigger a manual data collection immediately |
| `status` | Show scheduler status and next run time |
| `reset` | Clear last-run history (re-fetches all historical data on next run) |
| `exit` | Gracefully shut down |

---

## Configuration Reference

See [`scheduler_config.properties.example`](scheduler_config.properties.example) for all available options with descriptions.

Key settings:

| Key | Default | Description |
|---|---|---|
| `daily_run_time` | `09:00` | Time of day to run (HH:mm, 24h) |
| `repeat_interval_minutes` | `1440` | How often to repeat (1440 = daily) |
| `safe_data_range_days` | `3` | Days back to fetch on non-first runs |
| `initial_data_start_date` | `2024-01-01` | Start date for first historical pull |

---

## Output

- **JSON files** — saved to `data_files/`; this directory intentionally excludes runtime data from Git
- **SQL Server table** — `dbo.Motor_Data` (auto-created on first run if it doesn't exist)
- **Logs** — written to `logs/` directory

---

## Dependencies

| Library | Version | License |
|---|---|---|
| jackson-databind | 2.15.2 | Apache 2.0 |
| jackson-core | 2.15.2 | Apache 2.0 |
| jackson-annotations | 2.15.2 | Apache 2.0 |
| Microsoft JDBC Driver | 12.6.1 | MIT |

---

## License

This project is shared for educational/portfolio purposes. No warranty is provided.
