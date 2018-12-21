package com.agorapulse.micronaut.aws.dynamodb

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB
import com.amazonaws.services.dynamodbv2.datamodeling.*
import com.amazonaws.services.dynamodbv2.model.*
import groovy.transform.CompileDynamic
import groovy.util.logging.Slf4j

import java.lang.reflect.Method
import java.text.SimpleDateFormat

@Slf4j
class DefaultDynamoDBService<TItemClass> implements DynamoDBService<TItemClass> {

    static String INDEX_NAME_SUFFIX = 'Index'
    // Specific ranges ending with 'Index' are String concatenated indexes, to keep ordering (ex.: createdByUserIdIndex=37641047|2011-02-21T17:15:23.000Z|2424353910)
    public static final int DEFAULT_QUERY_LIMIT = 20
    public static final int DEFAULT_COUNT_LIMIT = 100
    public static final int BATCH_DELETE_LIMIT = 100
    public static final int WRITE_BATCH_SIZE = 100 // Max number of elements to write at once in DynamoDB (mixed tables)

    static String SERIALIZED_DATE_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
    static String SERIALIZED_DATE_TIMEZONE = 'GMT'


    protected final AmazonDynamoDB client
    protected final IDynamoDBMapper mapper

    protected DynamoDBMetadata<TItemClass> metadata

    /**
     * Initialize service for a given mapper class
     *
     * @param itemClass
     * @param amazonWebService
     */
    @CompileDynamic
    protected DefaultDynamoDBService(AmazonDynamoDB client, IDynamoDBMapper mapper, Class<TItemClass> itemClass) {
        this.client = client
        this.mapper = mapper
        this.metadata = DynamoDBMetadata.create(itemClass)
    }

    /**
     * Optional settings:
     * - consistentRead (default to false)
     * - limit (default to DEFAULT_COUNT_LIMIT)
     *
     * @param hashKey
     * @param rangeKeyName
     * @param rangeKeyValue
     * @param operator
     * @param settings
     * @return
     */
    int count(Object hashKey, String rangeKeyName, Object rangeKeyValue, ComparisonOperator operator, Map settings) {
        Map conditions = rangeKeyValue ? [(rangeKeyName): buildCondition(rangeKeyValue, operator)] : [:]
        countByConditions(hashKey, conditions, settings)
    }

    /**
     * Optional settings:
     * - consistentRead (default to false)
     * - limit (default to DEFAULT_COUNT_LIMIT)
     * - maxAfterDate (default to null)
     *
     * @param hashKey
     * @param rangeKeyName
     * @param rangeKeyDates
     * @param settings
     * @return
     */
    int countByDates(Object hashKey, String rangeKeyName, Map rangeKeyDates, Map settings) {
        Map conditions = buildDateConditions(rangeKeyName, rangeKeyDates, settings['maxAfterDate'] as Date)
        countByConditions(hashKey, conditions, settings)
    }

    /**
     * Optional settings:
     * - consistentRead (default to false)
     * - limit (default to DEFAULT_COUNT_LIMIT)
     *
     * @param hashKey
     * @param rangeKeyConditions
     * @param settings
     * @return
     */
    int countByConditions(Object hashKey, Map<String, Condition> rangeKeyConditions, Map settings) {
        settings = settings + [batchGetDisabled: true]
        if (!settings.limit) {
            settings = settings + [limit: DEFAULT_COUNT_LIMIT]
        }
        QueryResultPage resultPage = queryByConditions(hashKey, rangeKeyConditions, settings)
        resultPage?.results?.size() ?: 0
    }

    /**
     * Create the DynamoDB table for the given Class.
     *
     * @param classToCreate Class to create the table for
     * @param dynamoDB the dynamoDB client
     */
    CreateTableResult createTable(Long readCapacityUnits, Long writeCapacityUnits) {
        DynamoDBTable table = metadata.itemClass.getAnnotation(DynamoDBTable.class)

        try {
            // Check if the table exists
            client.describeTable(table.tableName())
            return null
        } catch (ResourceNotFoundException ignored) {
            CreateTableRequest createTableRequest = mapper.generateCreateTableRequest(metadata.itemClass)
            // new CreateTableRequest().withTableName(table.tableName())

            // ProvisionedThroughput
            ProvisionedThroughput provisionedThroughput = new ProvisionedThroughput()
                .withReadCapacityUnits(readCapacityUnits)
                .withWriteCapacityUnits(writeCapacityUnits)
            createTableRequest.setProvisionedThroughput(provisionedThroughput)

            // ProvisionedThroughput for GSIs
            if (createTableRequest.globalSecondaryIndexes) {
                createTableRequest.globalSecondaryIndexes.each { GlobalSecondaryIndex globalSecondaryIndex ->
                    provisionedThroughput = new ProvisionedThroughput()
                        .withReadCapacityUnits(2)
                        .withWriteCapacityUnits(2)
                    globalSecondaryIndex.setProvisionedThroughput(provisionedThroughput)
                    log.info("Creating DynamoDB GSI: ${globalSecondaryIndex}")
                }
            }

            log.info("Creating DynamoDB table: ${createTableRequest}")

            return client.createTable(createTableRequest)
        }
    }

    /**
     * Delete item by IDs.
     *
     * @param hashKey hash key of the item to delete
     * @param rangeKey range key of the item to delete
     * @param settings settings
     */
    void delete(Object hashKey, Object rangeKey, Map settings) {
        if (rangeKey) {
            delete(metadata.itemClass.newInstance((hashKeyName): hashKey, (rangeKeyName): rangeKey), settings)
            return
        }
        delete(metadata.itemClass.newInstance((hashKeyName): hashKey), settings)
    }

    /**
     * Delete a list of items from DynamoDB.
     *
     * @param itemsToDelete a list of objects to delete
     * @param settings settings
     */
    void deleteAll(List<TItemClass> itemsToDelete, Map settings) {
        if (!settings.containsKey('batchEnabled')) {
            settings = settings + [batchEnabled: true]
        }

        if (settings.batchEnabled && itemsToDelete.size() > 1) {
            itemsToDelete.collate(WRITE_BATCH_SIZE).each { List batchItems ->
                log.debug("Deleting items from DynamoDB ${batchItems}")
                mapper.batchDelete(batchItems)
            }
        } else {
            itemsToDelete.each {
                log.debug("Deleting item from DynamoDB ${it}")
                mapper.delete(it)
            }
        }
    }

    /**
     * Delete all items for a given hashKey and a rangeKey condition
     *
     * @param hashKey
     * @param rangeKeyName
     * @param rangeKeyValue
     * @param operator
     * @param settings
     * @return
     */
    int deleteAll(Object hashKey,
                  String rangeKeyName,
                  rangeKeyValue,
                  ComparisonOperator operator,
                  Map settings) {
        Map conditions = [(rangeKeyName): buildCondition(rangeKeyValue, operator)]
        deleteAllByConditions(
            hashKey,
            conditions,
            settings
        )
    }

    /**
     *
     * @param hashKey
     * @param rangeKeyConditions
     * @param settings
     * @param indexName
     * @return
     */
    int deleteAllByConditions(Object hashKey, Map<String, Condition> rangeKeyConditions, Map settings, String indexName) {
        if (!settings.containsKey('batchEnabled')) {
            settings = settings + [batchEnabled: true]
        }
        if (!settings.limit) {
            settings = settings + [limit: BATCH_DELETE_LIMIT]
        }

        DynamoDBQueryExpression query = buildQueryExpression(hashKeyName, hashKey, settings)
        query.hashKeyValues = metadata.itemClass.newInstance((hashKeyName): hashKey)
        if (rangeKeyConditions) {
            query.rangeKeyConditions = rangeKeyConditions
        }
        if (indexName) {
            query.indexName = indexName
        }

        deleteAllByConditions(query, settings)
    }

    /**
     *
     * @param hashKey
     * @param rangeKeyConditions
     * @param settings
     * @param indexName
     * @return
     */
    int deleteAllByConditions(DynamoDBQueryExpression query, Map settings) {
        QueryResultPage itemsPage = mapper.queryPage(metadata.itemClass, query)

        int deletedItemsCount = -1
        Map lastEvaluatedKey = itemsPage.lastEvaluatedKey
        while (lastEvaluatedKey || deletedItemsCount == -1) {
            if (deletedItemsCount == -1) {
                deletedItemsCount = 0
            } else {
                query.exclusiveStartKey = lastEvaluatedKey
            }
            itemsPage = mapper.queryPage(metadata.itemClass, query)
            if (itemsPage.results) {
                log.debug "Deleting ${itemsPage.results.size()} items, class: ${metadata.itemClass}"
                deletedItemsCount = deletedItemsCount + itemsPage.results.size()
                // Delete all items
                deleteAll(itemsPage.results, settings)
            }
            lastEvaluatedKey = itemsPage.lastEvaluatedKey
        }
        log.debug "Successfully deleted ${deletedItemsCount} items"
        deletedItemsCount
    }

    /**
     * Load an item
     *
     * @param hashKey
     * @param rangeKey
     * @return
     */
    TItemClass get(Object hashKey, Object rangeKey) {
        mapper.load(metadata.itemClass, hashKey, rangeKey)
    }

    /**
     * Retrieve batched items corresponding to a list of item IDs, in the same order.
     * Example: items = twitterItemDBService.getAll(1, [1, 2]).
     *
     * @param hashKey Hash Key of the items to retrieve
     * @param rangeKey Range keys of the items to retrieve
     * @param settings only used for setting throttle/readCapacityUnit when getting large sets
     * @return a list of DynamoDBItem
     */
    List<TItemClass> getAll(Object hashKey, List rangeKeys, Map settings) {
        Map result = [:]
        List objects = rangeKeys.unique(false).collect { it -> metadata.itemClass.newInstance((hashKeyName): hashKey, (rangeKeyName): it) }
        if (settings.throttle) {
            int resultCursor = 0
            long readCapacityUnit = settings.readCapacityUnit
            if (!readCapacityUnit) {
                DescribeTableResult tableResult = client.describeTable(metadata.mainTable.tableName())
                readCapacityUnit = tableResult?.getTable()?.provisionedThroughput?.readCapacityUnits ?: 10
            }
            objects.collate(20).each { List batchObjects ->
                result += mapper.batchLoad(batchObjects)
                resultCursor++
                if (readCapacityUnit && resultCursor >= (readCapacityUnit * 0.8)) {
                    resultCursor = 0
                    sleep(1000)
                }
            }
        } else {
            result = mapper.batchLoad(objects)
        }
        if (result[metadata.mainTable.tableName()]) {
            List unorderedItems = result[metadata.mainTable.tableName()]
            List items = []

            // Build an item list ordered in the same manner as the list of IDs we've been passed
            rangeKeys.each { rangeKey ->
                def matchingItem = unorderedItems.find { item ->
                    item[rangeKeyName] == rangeKey
                }
                if (matchingItem) {
                    items.add(matchingItem)
                }
                // Remove the matching item from the unordered list to reduce the number of loops in the find above
                unorderedItems.remove(matchingItem)
            }
            items
        } else {
            []
        }
    }

    /**
     * Increment a count with an atomic operation
     *
     * @param hashKey
     * @param rangeKey
     * @param attributeName
     * @param attributeIncrement
     * @return
     */
    Integer increment(Object hashKey, Object rangeKey, String attributeName, int attributeIncrement) {
        UpdateItemResult result = updateItemAttribute(hashKey, rangeKey, attributeName, attributeIncrement, AttributeAction.ADD)
        result?.attributes[attributeName]?.getN()?.toInteger()
    }

    TItemClass getNewInstance() {
        metadata.itemClass.newInstance()
    }

    PaginatedQueryList<TItemClass> query(DynamoDBQueryExpression queryExpression) {
        mapper.query(this.metadata.itemClass, queryExpression)
    }

    /**
     * Optional settings:
     * - batchGetDisabled (only when secondary indexes are used, useful for count when all item attributes are not required)
     * - consistentRead (default to false)
     * - exclusiveStartKey a map with the rangeKey (ex: [id: 2555]), with optional indexRangeKey when using LSI (ex.: [id: 2555, totalCount: 45])
     * - limit
     * - returnAll disable paging to return all items, WARNING: can be expensive in terms of throughput (default to false)
     * - scanIndexForward (default to false)
     *
     * @param hashKey
     * @param rangeKeyName
     * @param rangeKeyValue
     * @param operator
     * @param settings
     * @return
     */
    QueryResultPage<TItemClass> query(Object hashKey, String rangeKeyName, rangeKeyValue, ComparisonOperator operator, Map settings) {
        if (rangeKeyValue == 'ANY' || !operator) {
            if (!rangeKeyName.endsWith(INDEX_NAME_SUFFIX)) {
                rangeKeyName += INDEX_NAME_SUFFIX
            }
            queryByConditions(hashKey, [:], settings, rangeKeyName)
        } else {
            Map conditions = [(rangeKeyName): buildCondition(rangeKeyValue, operator)]
            queryByConditions(hashKey, conditions, settings)
        }
    }

    /**
     * Optional settings:
     * - batchGetDisabled (only when secondary indexes are used, useful for count when all item attributes are not required)
     * - consistentRead (default to false)
     * - exclusiveStartKey a map with the rangeKey (ex: [id: 2555]), with optional indexRangeKey when using LSI (ex.: [id: 2555, totalCount: 45])
     * - limit
     * - returnAll disable paging to return all items, WARNING: can be expensive in terms of throughput (default to false)
     * - scanIndexForward (default to false)
     * - throttle insert sleeps during execution to avoid reaching provisioned read throughput (default to false)
     *
     * @param hashKey
     * @param rangeKeyConditions
     * @param settings
     * @return
     */
    QueryResultPage<TItemClass> queryByConditions(Object hashKey, Map<String, Condition> rangeKeyConditions, Map settings, String indexName) {
        DynamoDBQueryExpression query = buildQueryExpression(hashKeyName, hashKey, settings)
        query.hashKeyValues = metadata.itemClass.newInstance((hashKeyName): hashKey)
        if (rangeKeyConditions) {
            query.rangeKeyConditions = rangeKeyConditions
        }
        if (indexName) {
            query.indexName = indexName
        }

        long readCapacityUnit = 0
        int resultCursor = 0
        QueryResultPage resultPage = new QueryResultPage()
        if (settings.returnAll) {
            // Get table read Throughput
            if (settings.throttle) {
                DescribeTableResult tableResult = client.describeTable(metadata.mainTable.tableName())
                readCapacityUnit = tableResult?.getTable()?.provisionedThroughput?.readCapacityUnits ?: 0
            }

            // Query all
            Map lastEvaluatedKey = [items: 'none']
            resultPage.results = []
            while (lastEvaluatedKey) {
                QueryResultPage currentPage = mapper.queryPage(metadata.itemClass, query)
                resultPage.results.addAll(currentPage.results)
                lastEvaluatedKey = currentPage.lastEvaluatedKey
                query.exclusiveStartKey = currentPage.lastEvaluatedKey
                if (settings.throttle) {
                    resultCursor++
                    if (readCapacityUnit && resultCursor >= (readCapacityUnit * 0.5)) {
                        resultCursor = 0
                        sleep(1000)
                    }
                }
            }
        } else {
            // Query page
            resultPage = mapper.queryPage(metadata.itemClass, query)
        }

        if (resultPage && (rangeKeyConditions || indexName) && !settings.batchGetDisabled) {
            // Indexes result only provides hash+range attributes, we need to batch get all items
            List rangeKeys = resultPage.results.collect { it[rangeKeyName] }
            if (rangeKeys) {
                resultPage.results = getAll(hashKey, rangeKeys, settings + [readCapacityUnit: readCapacityUnit])
            }
        }
        resultPage
    }

    /**
     * Query by dates with 'after' and/or 'before' range value
     * 1) After a certain date : [after: new Date()]
     * 2) Before a certain date : [before: new Date()]
     * 3) Between provided dates : [after: new Date() + 1, before: new Date()]
     *
     * Optional settings:
     * - batchGetDisabled (only when secondary indexes are used, useful for count when all item attributes are not required)
     * - consistentRead (default to false)
     * - exclusiveStartKey a map with the rangeKey (ex: [id: 2555]), with optional indexRangeKey when using LSI (ex.: [id: 2555, totalCount: 45])
     * - limit
     * - maxAfterDate (default to null)
     * - scanIndexForward (default to false)
     *
     * @param hashKey
     * @param rangeKeyName
     * @param rangeKeyDates
     * @param settings
     * @return
     */
    QueryResultPage<TItemClass> queryByDates(Object hashKey, String rangeKeyName, Map rangeKeyDates, Map settings) {
        Map conditions = buildDateConditions(rangeKeyName, rangeKeyDates, settings['maxAfterDate'] as Date)
        queryByConditions(hashKey, conditions, settings)
    }

    /**
     * Save a list of objects in DynamoDB.
     *
     * @param itemsToSave a list of objects to save
     * @param settings settings
     */
    List<TItemClass> saveAll(List<TItemClass> itemsToSave, Map settings) {
        if (!settings.containsKey('batchEnabled')) {
            settings = settings + [batchEnabled: true]
        }

        // Nullify empty collection properties
        itemsToSave.each { object ->
            nullifyHashSets(object)
        }

        log.debug "Saving items in DynamoDB ${itemsToSave}"

        if (settings.batchEnabled && itemsToSave.size() > 1) {
            itemsToSave.collate(WRITE_BATCH_SIZE).each { List<TItemClass> batchItems ->
                log.debug "Saving batched items in DynamoDB ${batchItems}"
                List failedBatchResult = settings.config ? mapper.batchSave(batchItems, settings.config) : mapper.batchSave(batchItems)
                if (failedBatchResult) {
                    failedBatchResult.each { DynamoDBMapper.FailedBatch failedBatch ->
                        int unprocessedItemsCount = 0
                        failedBatch.unprocessedItems.keySet().each { String key ->
                            unprocessedItemsCount += failedBatch.unprocessedItems.get(key).size()
                        }
                        log.error "Failed batch with ${unprocessedItemsCount} unprocessed items"
                        log.error "Exception: ${failedBatch.exception}"
                        throw failedBatch.exception
                    }
                }
            }
            return itemsToSave
        } else {
            itemsToSave.each {
                log.debug "Saving item in DynamoDB ${it}"
                settings.config ? mapper.save(it, settings.config as DynamoDBMapperConfig) : mapper.save(it)
            }
        }
    }

    /**
     * Delete a single item attribute
     *
     * @param hashKey
     * @param rangeKey
     * @param attributeName
     * @param attributeValue
     * @param action
     * @return
     */
    UpdateItemResult deleteItemAttribute(hashKey, rangeKey, String attributeName) {
        Map<String, Object> key = [(hashKeyName) : buildAttributeValue(hashKey)]
        if (rangeKey) {
            key[rangeKeyName] = buildAttributeValue(rangeKey)
        }
        UpdateItemRequest request = new UpdateItemRequest(
            tableName: metadata.mainTable.tableName(),
            key: key,
            returnValues: ReturnValue.UPDATED_NEW
        ).addAttributeUpdatesEntry(
            attributeName,
            new AttributeValueUpdate(
                action: AttributeAction.DELETE
            )
        )
        client.updateItem(request)
    }

    /**
     * Update a single item attribute
     *
     * @param hashKey
     * @param rangeKey
     * @param attributeName
     * @param attributeValue
     * @param action
     * @return
     */
    UpdateItemResult updateItemAttribute(hashKey, rangeKey, String attributeName, attributeValue, AttributeAction action = AttributeAction.PUT) {
        Map<String, Object> key = [(hashKeyName) : buildAttributeValue(hashKey)]
        if (rangeKey) {
            key[rangeKeyName] = buildAttributeValue(rangeKey)
        }
        UpdateItemRequest request = new UpdateItemRequest(
            tableName: metadata.mainTable.tableName(),
            key: key,
            returnValues: ReturnValue.UPDATED_NEW
        ).addAttributeUpdatesEntry(
            attributeName,
            new AttributeValueUpdate(
                action: action,
                value: buildAttributeValue(attributeValue)
            )
        )
        client.updateItem(request)
    }

    static String serializeDate(Date date) {
        SimpleDateFormat dateFormatter = new SimpleDateFormat(SERIALIZED_DATE_FORMAT)
        dateFormatter.timeZone = TimeZone.getTimeZone(SERIALIZED_DATE_TIMEZONE)
        dateFormatter.format(date)
    }

    /**
     *
     * @param params
     * @param maxAfterDate
     * @return
     */
    static protected Map addMaxAfterDateCondition(Map params,
                                                  Date maxAfterDate) {
        if (!params.containsKey('after')) {
            params['after'] = maxAfterDate
        } else if (((Date) params['after']).before(maxAfterDate)) {
            params['after'] = maxAfterDate
        }
        if (params.containsKey('before') && ((Date) params['before']).before((Date) params['after'])) {
            // Make sure that 'before' date is after 'after' date, to generate valid BETWEEN DynamoDB query (even if query will return nothing, it won't break)
            params['before'] = params['after']
        }
        params
    }

    /**
     *
     * @param key
     * @return
     */
    static protected AttributeValue buildAttributeValue(Object key) {
        if (key instanceof Number) {
            new AttributeValue().withN(key.toString())
        } else if (key instanceof Boolean) {
            new AttributeValue().withN(key ? "1" : "0")
        } else if (key instanceof Date) {
            new AttributeValue().withS(serializeDate(key))
        } else {
            new AttributeValue().withS(key.toString())
        }
    }

    /**
     *
     * @param rangeKeyValue
     * @param operator
     * @return
     */
    static protected Condition buildCondition(rangeKeyValue,
                                              ComparisonOperator operator = ComparisonOperator.EQ) {
        new Condition()
            .withComparisonOperator(operator)
            .withAttributeValueList(buildAttributeValue(rangeKeyValue))
    }

    /**
     *
     * @param rangeKeyName
     * @param rangeKeyDates
     * @param maxAfterDate
     * @return
     */
    static protected Map buildDateConditions(String rangeKeyName,
                                             Map<String, Date> rangeKeyDates,
                                             Date maxAfterDate = null) {
        assert rangeKeyDates.keySet().any { it in ['after', 'before'] }
        ComparisonOperator operator
        List attributeValueList = []
        if (maxAfterDate) {
            addMaxAfterDateCondition(rangeKeyDates, maxAfterDate)
        }
        if (rangeKeyDates.containsKey('after')) {
            operator = ComparisonOperator.GE
            attributeValueList << new AttributeValue().withS(serializeDate(rangeKeyDates['after']))
        }
        if (rangeKeyDates.containsKey('before')) {
            operator = ComparisonOperator.LE
            attributeValueList << new AttributeValue().withS(serializeDate(rangeKeyDates['before']))
        }
        if (rangeKeyDates.containsKey('after') && rangeKeyDates.containsKey('before')) {
            operator = ComparisonOperator.BETWEEN
        }
        [
            (rangeKeyName): new Condition()
                .withComparisonOperator(operator)
                .withAttributeValueList(attributeValueList)
        ]
    }

    /**
     *
     * @param hashKeyName
     * @param hashKey
     * @param settings
     * @return
     */
    @CompileDynamic
    static protected DynamoDBQueryExpression buildQueryExpression(Object hashKeyName,
                                                                  Object hashKey,
                                                                  Map settings = [:]) {
        DynamoDBQueryExpression query = new DynamoDBQueryExpression()
        if (settings.containsKey('consistentRead')) {
            query.consistentRead = settings.consistentRead
        }
        if (settings.exclusiveStartKey) {
            assert settings.exclusiveStartKey instanceof Map
            query.exclusiveStartKey = buildStartKey(settings.exclusiveStartKey + [(hashKeyName): hashKey])
        }
        if (settings.limit) {
            assert settings.limit.toString().isNumber()
            query.limit = settings.limit
        } else {
            query.limit = DEFAULT_QUERY_LIMIT
        }
        if (settings.containsKey('scanIndexForward')) {
            query.scanIndexForward = settings.scanIndexForward
        } else {
            query.scanIndexForward = false
        }
        query
    }

    /**
     * Transform a simple map (Map<Object, Object>) into a {@link Map} of {@link com.amazonaws.services.dynamodbv2.model.AttributeValue} (Map<Object, AttributeValue>).
     *
     * @param map the {@link Map} to transform
     * @return an exclusiveStartKey ready to use in a {@link com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBQueryExpression}
     */
    static protected Map buildStartKey(Map map) {
        map.inject([:]) { startKey, it ->
            if (it.value instanceof AttributeValue) {
                startKey[it.key] = it.value
            } else {
                startKey[it.key] = buildAttributeValue(it.value)
            }
            startKey
        } as Map
    }

    @CompileDynamic
    static void nullifyHashSets(Object object) {
        object.properties.each { String prop, val ->
            if (object.hasProperty(prop)
                && object[prop] instanceof HashSet
                && object[prop]?.size() == 0) {
                try {
                    // log.debug("Nullifying collection ${prop} before sending to DynamoDB")
                    String getterName = "get" + prop.substring(0, 1).toUpperCase() + prop.substring(1);
                    Method getter = object.getClass().getMethod(getterName)
                    if (getter != null && getter.getAnnotation(DynamoDBIgnore.class) == null) {
                        object[prop] = null
                    }
                } catch (Exception e) {
                    log.error "failed to nullify collection ${prop} of ${object} before sending to DynamoDB", e
                }
            }
        }
    }

    boolean isIndexRangeKey(String rangeName) {
        metadata.secondaryIndexes.contains(rangeName)
    }

    String getHashKeyName() {
        return metadata.hashKeyName
    }

    Class getHashKeyClass() {
        return metadata.hashKeyClass
    }

    String getRangeKeyName() {
        return metadata.rangeKeyName
    }

    Class getRangeKeyClass() {
        return metadata.rangeKeyClass
    }

}