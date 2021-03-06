package net.openhft.chronicle.queue;

import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.OS;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.io.IOTools;
import net.openhft.chronicle.queue.impl.single.SingleChronicleQueueBuilder;
import net.openhft.chronicle.wire.Marshallable;
import net.openhft.chronicle.wire.MethodReader;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Created by peter on 08/04/16.
 */
public class JDBCServiceTest {

    @Test
    public void testCreateTable() throws SQLException, IOException {
        doCreateTable(4, 10000);
    }

    @Test
    @Ignore("Long running")
    public void perfCreateTable() throws SQLException, IOException {
        doCreateTable(5, 200000);
    }

    public void doCreateTable(int repeats, int noUpdates) throws SQLException, IOException {
        for (int t = 0; t < repeats; t++) {
            long start = System.nanoTime(), written;
            String path1 = OS.TMP + "/createTable-" + System.nanoTime();
            String path2 = OS.TMP + "/createTable-" + System.nanoTime();
            File file = new File(OS.TARGET, "hsqldb-" + System.nanoTime());
            file.deleteOnExit();

            try (ChronicleQueue in = SingleChronicleQueueBuilder.binary(path1).build();
                 ChronicleQueue out = SingleChronicleQueueBuilder.binary(path2).build()) {

                JDBCService service = new JDBCService(in, out, () -> DriverManager.getConnection("jdbc:hsqldb:file:" + file.getAbsolutePath(), "SA", ""));

                JDBCStatement writer = service.createWriter();
                writer.executeUpdate("CREATE TABLE tableName (\n" +
                        "name VARCHAR(64) NOT NULL,\n" +
                        "num INT\n" +
                        ")\n");

                for (int i = 1; i < (long) noUpdates; i++)
                    writer.executeUpdate("INSERT INTO tableName (name, num)\n" +
                            "VALUES (?, ?)", "name", i);

                written = System.nanoTime() - start;
                AtomicLong queries = new AtomicLong();
                AtomicLong updates = new AtomicLong();
                CountingJDBCResult countingJDBCResult = new CountingJDBCResult(queries, updates);
                MethodReader methodReader = service.createReader(countingJDBCResult);
                int counter = 0;
                while (updates.get() < noUpdates) {
                    if (methodReader.readOne())
                        counter++;
                    else
                        Thread.yield();
                }
                Closeable.closeQuietly(service);

//            System.out.println(in.dump());
//            System.out.println(out.dump());

                long time = System.nanoTime() - start;
                System.out.printf("Average time to write each update %.1f us, average time to perform each update %.1f us%n",
                        written / noUpdates / 1e3,
                        time / noUpdates / 1e3);
            } finally {
                try {
                    IOTools.deleteDirWithFiles(path1, 2);
                    IOTools.deleteDirWithFiles(path2, 2);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }


    private static class CountingJDBCResult implements JDBCResult {
        private final AtomicLong queries;
        private final AtomicLong updates;

        public CountingJDBCResult(AtomicLong queries, AtomicLong updates) {
            this.queries = queries;
            this.updates = updates;
        }

        @Override
        public void queryResult(Iterator<Marshallable> marshallableList, String query, Object... args) {
            queries.incrementAndGet();
        }

        @Override
        public void queryThrown(Throwable t, String query, Object... args) {
            throw Jvm.rethrow(t);
        }

        @Override
        public void updateResult(long count, String update, Object... args) {
            updates.incrementAndGet();
        }

        @Override
        public void updateThrown(Throwable t, String update, Object... args) {
            throw Jvm.rethrow(t);
        }
    }
}
