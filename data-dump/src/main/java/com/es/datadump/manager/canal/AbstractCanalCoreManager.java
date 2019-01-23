package com.es.datadump.manager.canal;

import com.alibaba.otter.canal.client.CanalConnector;
import com.alibaba.otter.canal.protocol.CanalEntry.*;
import com.alibaba.otter.canal.protocol.Message;
import com.es.datadump.manager.ServiceImportManager;
import com.es.datadump.util.CharSetUtil;
import com.es.stone.constant.EsConstant;
import com.es.stone.manager.ElasticSearchDumpManager;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import org.apache.commons.lang.SystemUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.nio.charset.CharacterCodingException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 同步核心类
 *
 * @author
 */
public class AbstractCanalCoreManager {

    private final static Logger logger = LoggerFactory.getLogger(AbstractCanalCoreManager.class);
    private static final String SEP = SystemUtils.LINE_SEPARATOR;
    private static final String DATE_FORMAT = "yyyy-MM-dd HH:mm:ss";
    private volatile boolean running = false;
    private Thread.UncaughtExceptionHandler handler = (t, e) -> logger.error("parse events has an error", e);
    private Thread thread = null;
    private CanalConnector canalConnector;
    private static String context_format;
    private static String row_format;
    private static String transaction_format;
    private String destination;

    private ElasticSearchDumpManager elasticSearchDumpManager;
    private ServiceImportManager serviceImportManager;
    private SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private SimpleDateFormat sdfDate = new SimpleDateFormat("yyyy-MM-dd");
    private SimpleDateFormat sdfTime = new SimpleDateFormat("HH:mm:ss");

    static {
        context_format = SEP + "****************************************************" + SEP;
        context_format += "* Batch Id: [{}] ,count : [{}] , memSize : [{}] , Time : {}" + SEP;
        context_format += "* Start : [{}] " + SEP;
        context_format += "* End : [{}] " + SEP;
        context_format += "****************************************************" + SEP;

        row_format = SEP
                + "----------------> binlog[{}:{}] , name[{},{}] , eventType : {} , executeTime : {} , delay : {}ms"
                + SEP;

        transaction_format = SEP + "================> binlog[{}:{}] , executeTime : {} , delay : {}ms" + SEP;

    }

    public AbstractCanalCoreManager(String destination) {
        this(destination, null);
    }

    public AbstractCanalCoreManager(String destination, CanalConnector connector) {
        this.destination = destination;
        this.canalConnector = connector;
    }

    public void start() {
        Assert.notNull(canalConnector, "connector is null");
        thread = new Thread(() -> process());

        thread.setUncaughtExceptionHandler(handler);
        thread.start();
        running = true;
    }

    public void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (thread != null) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                logger.error("canal线程join异常:", e);
            }
        }

        MDC.remove("destination");
    }

    protected void process() {
        int batchSize = 128;
        while (running) {
            try {
                MDC.put("destination", destination);
                canalConnector.connect();
                canalConnector.subscribe();
                while (running) {
                    // 获取指定数量的数据
                    Message message = canalConnector.getWithoutAck(batchSize);
                    long batchId = message.getId();
                    int size = message.getEntries().size();
                    if (batchId == -1 || size == 0) {
                        // try {
                        // Thread.sleep(1000);
                        // } catch (InterruptedException e) {
                        // }
                    } else {
                        printSummary(message, batchId, size);
                        syncEntry(message.getEntries());
                    }

                    // 提交确认
                    canalConnector.ack(batchId);
                    // 处理失败, 回滚数据
                    // connector.rollback(batchId)
                }
            } catch (Exception e) {
                logger.error("process error!", e);
            } finally {
                canalConnector.disconnect();
                MDC.remove("destination");
            }
        }
    }

    private void printSummary(Message message, long batchId, int size) {
        long memsize = 0;
        for (Entry entry : message.getEntries()) {
            memsize += entry.getHeader().getEventLength();
        }

        String startPosition = null;
        String endPosition = null;
        if (!CollectionUtils.isEmpty(message.getEntries())) {
            startPosition = buildPositionForDump(message.getEntries().get(0));
            endPosition = buildPositionForDump(message.getEntries().get(message.getEntries().size() - 1));
        }

        SimpleDateFormat format = new SimpleDateFormat(DATE_FORMAT);
        logger.info(context_format, batchId, size, memsize, format.format(new Date()), startPosition,
                endPosition);
    }

    protected String buildPositionForDump(Entry entry) {
        long time = entry.getHeader().getExecuteTime();
        Date date = new Date(time);
        SimpleDateFormat format = new SimpleDateFormat(DATE_FORMAT);
        return entry.getHeader().getLogfileName() + ":" + entry.getHeader().getLogfileOffset() + ":"
                + entry.getHeader().getExecuteTime() + "(" + format.format(date) + ")";
    }

    /**
     * 数据同步顶层方法，加入事物判断。
     *
     * @param entries
     * @throws Exception
     */
    protected void syncEntry(List<Entry> entries) throws Exception {
        //开固定数量的线程跑
        //1、创建固定线程池。
        ExecutorService syncEntryThreadPool = Executors.newFixedThreadPool(5);
        int i = 0;
        List<Entry> subEntries = new ArrayList<>();
        for (Entry entry : entries) {
            subEntries.add(entry);
            syncEntryThreadPool.submit(new SyncDataThread(i, subEntries));
            subEntries = new ArrayList<>();
            i++;
        }

        try {
            syncEntryThreadPool.shutdown();
            syncEntryThreadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            logger.error("线程池超时无法释放！", e);
            throw new Exception(e);
        }

    }

    /**
     * 解析mysql的binlog方法，返回key列名和值value
     *
     * @param columns
     * @return
     */
    protected Map analysisColumn(List<Column> columns) {
        Map colMap = Collections.synchronizedMap(new HashMap());
        boolean errorLog = false;

        //根据mysql的主键字段名升序排列
        Map<String, String> keyMap = new TreeMap<String, String>(
                Comparator.naturalOrder());

        for (Column column : columns) {
            if (column.getIsKey()) {
                keyMap.put(column.getName(), column.getValue());
                colMap.put(column.getName(), column.getValue());
            }
            if ("timestamp".equals(column.getMysqlType()) || "datetime".equals(column.getMysqlType())) {
                if (StringUtils.isNotBlank(column.getValue())) {
                    try {
                        synchronized (sdf) {
                            //时间戳单独处理存入ES
                            colMap.put(column.getName(), sdf.parse(column.getValue()));
                        }
                    } catch (Exception e) {
                        logger.error("数据库timestamp / datetime字段映射异常:key: {},value: {} ", column.getName(), column.getValue(), e);
                        errorLog = true;
                    }
                }
            } else if ("date".equals(column.getMysqlType())) {
                //一定要在此处加判断，如果为空，则不走下面的else分支，不组装date字段，否则es报错。
                if (StringUtils.isNotBlank(column.getValue())) {
                    try {
                        synchronized (sdfDate) {
                            //时间戳单独处理存入ES
                            colMap.put(column.getName(), sdfDate.parse(column.getValue()));
                        }
                    } catch (Exception e) {
                        logger.error("数据库date字段映射异常: key: {},value: {}", column.getName(), column.getValue(), e);
                        errorLog = true;
                    }
                }
            } else if ("time".equals(column.getMysqlType())) {
                //一定要在此处加判断，如果为空，则不走下面的else分支，不组装time字段，否则es报错。
                if (StringUtils.isNotBlank(column.getValue())) {
                    try {
                        synchronized (sdfTime) {
                            //时间戳单独处理存入ES
                            colMap.put(column.getName(), sdfTime.parse(column.getValue()));
                        }
                    } catch (Exception e) {
                        logger.error("数据库time字段映射异常: key: {},value: {}", column.getName(), column.getValue(), e);
                        errorLog = true;
                    }
                }
            } else if ("blob".equals(column.getMysqlType())) {
                ByteString bs = column.getValueBytes();
                try {
                    String blobVal = CharSetUtil.bytesToString(bs.toByteArray(), "ISO-8859-1", "UTF-8");
                    colMap.put(column.getName(), blobVal);
                } catch (CharacterCodingException e) {
                    logger.error("数据库blob字段解析异常： {},value: {} ", column.getName(), column.getValue(), e);
                    errorLog = true;
                }
            } else {
                colMap.put(column.getName(), column.getValue());
            }
        }
        colMap.put(EsConstant.ES_KEY, elasticSearchDumpManager.esKeyString(keyMap));
        if (errorLog) {
            logger.error("the PK value in ES is: {}", colMap.get(EsConstant.ES_KEY));
        }

        return colMap;
    }

    private class SyncDataThread implements Callable<Integer> {
        private int threadId;
        private List<Entry> entryList;

        public SyncDataThread(int id, List<Entry> entryList) {
            this.threadId = id;
            this.entryList = entryList;
        }

        @Override
        public Integer call() throws Exception {
            for (Entry entry : entryList) {

                long executeTime = entry.getHeader().getExecuteTime();
                long delayTime = System.currentTimeMillis() - executeTime;

                //判断mysql事物开始和事物结束
                if (entry.getEntryType() == EntryType.TRANSACTIONBEGIN || entry.getEntryType() == EntryType.TRANSACTIONEND) {
                    if (entry.getEntryType() == EntryType.TRANSACTIONBEGIN) {
                        TransactionBegin begin = null;
                        try {
                            begin = TransactionBegin.parseFrom(entry.getStoreValue());
                        } catch (InvalidProtocolBufferException e) {
                            throw new RuntimeException("parse event has an error , data:" + entry.toString(), e);
                        }
                        // 打印事务头信息，执行的线程id，事务耗时
                        logger.info(transaction_format,
                                entry.getHeader().getLogfileName(),
                                String.valueOf(entry.getHeader().getLogfileOffset()),
                                String.valueOf(entry.getHeader().getExecuteTime()), String.valueOf(delayTime));
                        logger.info(" BEGIN ----> Thread id: {}", begin.getThreadId());
                    } else {
                        TransactionEnd end;
                        try {
                            end = TransactionEnd.parseFrom(entry.getStoreValue());
                        } catch (InvalidProtocolBufferException e) {
                            throw new RuntimeException("parse event has an error , data:" + entry.toString(), e);
                        }
                        // 打印事务提交信息，事务id
                        logger.info("----------------\n");
                        logger.info(" END ----> transaction id: {}", end.getTransactionId());
                        logger.info(transaction_format,
                                entry.getHeader().getLogfileName(),
                                String.valueOf(entry.getHeader().getLogfileOffset()),
                                String.valueOf(entry.getHeader().getExecuteTime()), String.valueOf(delayTime));
                    }

                }

                //解析mysql逐行数据
                if (entry.getEntryType() == EntryType.ROWDATA) {
                    RowChange rowChange;
                    try {
                        rowChange = RowChange.parseFrom(entry.getStoreValue());
                    } catch (Exception e) {
                        throw new RuntimeException("parse event has an error , data:" + entry.toString(), e);
                    }

                    EventType eventType = rowChange.getEventType();

                    logger.info(row_format,
                            entry.getHeader().getLogfileName(),
                            String.valueOf(entry.getHeader().getLogfileOffset()), entry.getHeader().getSchemaName(),
                            entry.getHeader().getTableName(), eventType,
                            String.valueOf(entry.getHeader().getExecuteTime()), String.valueOf(delayTime));

                    if (eventType == EventType.QUERY || rowChange.getIsDdl()) {
                        logger.info(" sql ----> " + rowChange.getSql() + SEP);
                    }

                    //开启线程逐行同步数据
                    if (rowChange.getRowDatasList().size() > 0) {
                        ExecutorService syncRowDataThreadPool = Executors.newFixedThreadPool(5);
                        List<RowData> rowDataList = new ArrayList<>();
                        int i = 0;
                        for (RowData rowData : rowChange.getRowDatasList()) {
                            if (rowDataList.size() < 4) {
                                rowDataList.add(rowData);
                            } else {
                                //第10条加入到列表，然后利用线程池，开启线程同步
                                rowDataList.add(rowData);
                                syncRowDataThreadPool.submit(new SyncRowDataThread(i, rowDataList, entry, eventType));
                                //创建新List对象。用于开启新线程。
                                rowDataList = new ArrayList<>();
                            }
                        }
                        //如果rowDataList中不足10条，则需要将不足的，提交到线程执行
                        if (rowDataList.size() < 4) {
                            syncRowDataThreadPool.submit(new SyncRowDataThread(i, rowDataList, entry, eventType));
                        }
                        try {
                            syncRowDataThreadPool.shutdown();
                            syncRowDataThreadPool.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
                        } catch (Exception e) {
                            logger.error("线程池超时无法释放！", e);
                            throw new Exception(e);
                        }
                    }
                }
            }

            return threadId;
        }

    }

    private class SyncRowDataThread implements Callable<Integer> {
        private int threadId;
        private List<RowData> rowDataList;
        private Entry entry;
        private EventType eventType;

        public SyncRowDataThread(int id, List<RowData> rowDataList, Entry entry, EventType eventType) {
            this.threadId = id;
            this.rowDataList = rowDataList;
            this.entry = entry;
            this.eventType = eventType;
        }

        @Override
        public Integer call() throws Exception {
            Map colMap;
            for (RowData rowData : rowDataList) {
                if (eventType == EventType.DELETE) {
                    colMap = analysisColumn(rowData.getBeforeColumnsList());
                    elasticSearchDumpManager.deleteRecordToEs(colMap, entry.getHeader().getSchemaName() + "." + entry.getHeader().getTableName());
                } else {
                    colMap = analysisColumn(rowData.getAfterColumnsList());
                    serviceImportManager.getDateMap(colMap, entry.getHeader().getSchemaName() + "." + entry.getHeader().getTableName());
                }
            }
            return threadId;
        }

    }

    public void setCanalConnector(CanalConnector canalConnector) {
        this.canalConnector = canalConnector;
    }

    public void setElasticSearchDumpManager(ElasticSearchDumpManager elasticSearchDumpManager) {
        this.elasticSearchDumpManager = elasticSearchDumpManager;
    }

    public void setServiceImportManager(ServiceImportManager serviceImportManager) {
        this.serviceImportManager = serviceImportManager;
    }

}
