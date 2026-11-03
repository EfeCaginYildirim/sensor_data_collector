# Third-party notices

Sensor Data Collector is licensed under the MIT License (see `LICENSE`). The distributed jar and
desktop packages also contain the following components, each under its own license.

| Component | Version | License | Where |
|---|---|---|---|
| [Jackson Databind, Core, Annotations](https://github.com/FasterXML/jackson) | 2.17.2 | Apache License 2.0 | jar, packages |
| [H2 Database Engine](https://h2database.com) | 2.3.232 | MPL 2.0 or EPL 1.0 | jar, packages |
| [Microsoft JDBC Driver for SQL Server](https://github.com/microsoft/mssql-jdbc) | 12.8.1 | MIT | jar, packages |
| [uPlot](https://github.com/leeoniya/uPlot) | 1.6.32 | MIT | jar, packages (`ui/vendor/uplot.LICENSE.txt`) |
| [Eclipse Temurin](https://adoptium.net) (OpenJDK runtime) | 21 | GPL 2.0 with Classpath Exception | packages only |

The jar keeps each dependency's own license and notice files under `META-INF/`, and this file and
`LICENSE` under `META-INF/sensor-data-collector/`. In the desktop packages, both files sit next to
the jar in the `app` folder, and the trimmed Java runtime carries the OpenJDK license texts in its
`legal` folder (`SensorDataCollector.app/Contents/runtime/Contents/Home/legal` on macOS,
`SensorDataCollector/runtime/legal` on Windows).

The source code of H2 is available at https://github.com/h2database/h2database, and of the OpenJDK
runtime at https://github.com/adoptium/jdk21u.

JUnit 5 (EPL 2.0) is used only to run the tests and is not distributed.
