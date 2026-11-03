package io.github.efecaginyildirim.collector.persistence;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * A recording fake of the JDBC interfaces the repository uses, built with dynamic proxies.
 *
 * <p>It does not interpret SQL. It records what was executed, with which parameters, in which
 * order and under which transaction state, so tests can verify the repository's contract with
 * the database without a SQL Server instance.
 */
final class RecordingJdbc {

    /** Marker for a parameter bound with {@code setNull}. */
    record SqlNull(int sqlType) { }

    /**
     * One statement execution or batched row.
     *
     * @param kind   {@code update}, {@code batchRow}, {@code query} or {@code execute}
     * @param sql    statement text
     * @param params bound parameters by index
     */
    record Execution(String kind, String sql, Map<Integer, Object> params) {
        long placeholders() {
            return sql.chars().filter(ch -> ch == '?').count();
        }
    }

    final List<String> log = Collections.synchronizedList(new ArrayList<>());
    final List<Execution> executions = Collections.synchronizedList(new ArrayList<>());
    Predicate<String> failOn = sql -> false;
    ToIntFunction<String> updateCount = sql -> 1;
    List<Object[]> queryRows = List.of();
    int openConnections;

    JdbcMotorDataRepository.ConnectionFactory factory() {
        return this::connection;
    }

    List<Execution> executions(String kind) {
        return executions.stream().filter(e -> e.kind().equals(kind)).toList();
    }

    private Connection connection() {
        openConnections++;
        boolean[] autoCommit = {true};
        return proxy(Connection.class, (p, m, args) -> switch (m.getName()) {
            case "prepareStatement" -> preparedStatement((String) args[0]);
            case "createStatement" -> statement();
            case "setAutoCommit" -> {
                autoCommit[0] = (boolean) args[0];
                log.add("autoCommit=" + autoCommit[0]);
                yield null;
            }
            case "getAutoCommit" -> autoCommit[0];
            case "commit" -> { log.add("commit"); yield null; }
            case "rollback" -> { log.add("rollback"); yield null; }
            case "close" -> { log.add("close"); openConnections--; yield null; }
            case "isClosed" -> false;
            default -> unsupported(m);
        });
    }

    private Statement statement() {
        return proxy(Statement.class, (p, m, args) -> switch (m.getName()) {
            case "execute" -> {
                String sql = (String) args[0];
                maybeFail(sql);
                executions.add(new Execution("execute", sql, Map.of()));
                log.add("execute");
                yield false;
            }
            case "close" -> null;
            default -> unsupported(m);
        });
    }

    private PreparedStatement preparedStatement(String sql) {
        Map<Integer, Object> params = new TreeMap<>();
        int[] batched = {0};
        log.add("prepare " + firstWords(sql));
        return proxy(PreparedStatement.class, (p, m, args) -> switch (m.getName()) {
            case "setInt", "setString", "setObject", "setDouble" -> {
                params.put((Integer) args[0], args[1]);
                yield null;
            }
            case "setNull" -> {
                params.put((Integer) args[0], new SqlNull((Integer) args[1]));
                yield null;
            }
            case "addBatch" -> {
                executions.add(new Execution("batchRow", sql, new TreeMap<>(params)));
                params.clear();
                batched[0]++;
                yield null;
            }
            case "executeBatch" -> {
                maybeFail(sql);
                log.add("executeBatch " + batched[0]);
                int[] counts = new int[batched[0]];
                java.util.Arrays.fill(counts, 1);
                batched[0] = 0;
                yield counts;
            }
            case "executeUpdate" -> {
                maybeFail(sql);
                executions.add(new Execution("update", sql, new TreeMap<>(params)));
                log.add("executeUpdate " + firstWords(sql));
                yield updateCount.applyAsInt(sql);
            }
            case "executeQuery" -> {
                maybeFail(sql);
                executions.add(new Execution("query", sql, new TreeMap<>(params)));
                yield resultSet(queryRows);
            }
            case "close" -> null;
            default -> unsupported(m);
        });
    }

    private ResultSet resultSet(List<Object[]> rows) {
        int[] cursor = {-1};
        return proxy(ResultSet.class, (p, m, args) -> switch (m.getName()) {
            case "next" -> ++cursor[0] < rows.size();
            case "getObject" -> {
                Object v = rows.get(cursor[0])[(Integer) args[0] - 1];
                yield args.length == 2 ? ((Class<?>) args[1]).cast(v) : v;
            }
            case "close" -> null;
            default -> unsupported(m);
        });
    }

    private void maybeFail(String sql) throws SQLException {
        if (failOn.test(sql)) {
            log.add("FAIL " + firstWords(sql));
            throw new SQLException("injected failure: " + firstWords(sql));
        }
    }

    static String firstWords(String sql) {
        String[] words = sql.strip().split("\\s+");
        return String.join(" ", java.util.Arrays.copyOf(words, Math.min(3, words.length)));
    }

    private static Object unsupported(Method m) {
        throw new UnsupportedOperationException("RecordingJdbc does not implement " + m.getName());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(RecordingJdbc.class.getClassLoader(), new Class<?>[]{type}, (p, m, args) -> {
            if (m.getDeclaringClass() == Object.class) {
                return switch (m.getName()) {
                    case "toString" -> "RecordingJdbc." + type.getSimpleName();
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                };
            }
            return handler.invoke(p, m, args);
        });
    }
}
