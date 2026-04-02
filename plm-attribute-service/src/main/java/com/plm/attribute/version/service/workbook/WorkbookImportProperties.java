package com.plm.attribute.version.service.workbook;

public class WorkbookImportProperties {

    private Async async = new Async();
    private Runtime runtime = new Runtime();

    public Async getAsync() {
        return async;
    }

    public void setAsync(Async async) {
        this.async = async;
    }

    public Runtime getRuntime() {
        return runtime;
    }

    public void setRuntime(Runtime runtime) {
        this.runtime = runtime;
    }

    public static class Async {

        private String threadNamePrefix = "workbook-import-";
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        private int queueCapacity = 32;

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }

    public static class Runtime {

        private long cleanupIntervalMillis = 600_000L;
        private long emitterTimeoutMillis = 1_800_000L;
        private long sessionRetentionMillis = 86_400_000L;
        private long snapshotRetentionMillis = 7_200_000L;
        private long terminalJobRetentionMillis = 3_600_000L;

        public long getCleanupIntervalMillis() {
            return cleanupIntervalMillis;
        }

        public void setCleanupIntervalMillis(long cleanupIntervalMillis) {
            this.cleanupIntervalMillis = cleanupIntervalMillis;
        }

        public long getEmitterTimeoutMillis() {
            return emitterTimeoutMillis;
        }

        public void setEmitterTimeoutMillis(long emitterTimeoutMillis) {
            this.emitterTimeoutMillis = emitterTimeoutMillis;
        }

        public long getSessionRetentionMillis() {
            return sessionRetentionMillis;
        }

        public void setSessionRetentionMillis(long sessionRetentionMillis) {
            this.sessionRetentionMillis = sessionRetentionMillis;
        }

        public long getSnapshotRetentionMillis() {
            return snapshotRetentionMillis;
        }

        public void setSnapshotRetentionMillis(long snapshotRetentionMillis) {
            this.snapshotRetentionMillis = snapshotRetentionMillis;
        }

        public long getTerminalJobRetentionMillis() {
            return terminalJobRetentionMillis;
        }

        public void setTerminalJobRetentionMillis(long terminalJobRetentionMillis) {
            this.terminalJobRetentionMillis = terminalJobRetentionMillis;
        }
    }
}
