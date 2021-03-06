package com.smoke.xiguazi.service.impl;

import com.alibaba.fastjson.JSON;
import com.smoke.xiguazi.mapper.CarInfoMapper;
import com.smoke.xiguazi.mapper.TransactionInfoMapper;
import com.smoke.xiguazi.model.po.CarInfo;
import com.smoke.xiguazi.model.po.TransactionInfo;
import com.smoke.xiguazi.model.vo.SearchPageVo;
import com.smoke.xiguazi.model.vo.TransSearchParam;
import com.smoke.xiguazi.model.vo.TransVo;
import com.smoke.xiguazi.service.ElasticsearchService;
import com.smoke.xiguazi.utils.ConstUtil;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest;
import org.elasticsearch.action.bulk.BulkRequest;
import org.elasticsearch.action.bulk.BulkResponse;
import org.elasticsearch.action.delete.DeleteRequest;
import org.elasticsearch.action.delete.DeleteResponse;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.action.index.IndexResponse;
import org.elasticsearch.action.search.SearchRequest;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.action.support.master.AcknowledgedResponse;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.indices.CreateIndexRequest;
import org.elasticsearch.client.indices.CreateIndexResponse;
import org.elasticsearch.client.indices.GetIndexRequest;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.SearchHit;
import org.elasticsearch.search.builder.SearchSourceBuilder;
import org.elasticsearch.search.sort.FieldSortBuilder;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.ResourceUtils;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ElasticsearchServiceImpl implements ElasticsearchService {
    private final RestHighLevelClient client;
    private final CarInfoMapper carInfoMapper;
    private final TransactionInfoMapper transactionInfoMapper;

    Map<String, String> carMakeMap;
    Map<String, String> cityMap;

    @Autowired
    public ElasticsearchServiceImpl(
            @Qualifier("restHighLevelClient") RestHighLevelClient restHighLevelClient, CarInfoMapper carInfoMapper,
            TransactionInfoMapper transactionInfoMapper, Map<String, String> carMakeMap, Map<String, String> cityMap) {
        this.client = restHighLevelClient;
        this.carInfoMapper = carInfoMapper;
        this.transactionInfoMapper = transactionInfoMapper;
        this.carMakeMap = carMakeMap;
        this.cityMap = cityMap;
    }

    /**
     * ??????index
     * @param index
     * @return
     * @throws IOException
     */
    @Override
    public boolean rebuildIndex(String index) throws IOException {
        //  ??????index????????????
        boolean exists = existsIndex(index);

        //  ????????????????????????index
        if(exists){
            boolean deleteResult = deleteIndex(index);
        }

        //  ??????index
        boolean isAcknowledged = createIndex(index);

        return isAcknowledged;
    }

    /**
     *  ??????index????????????
     * @param index
     * @return  boolean
     * @throws IOException
     */
    @Override
    public boolean existsIndex(String index) throws IOException {
        GetIndexRequest getIndexRequest = new GetIndexRequest(index);
        boolean exists = client.indices().exists(getIndexRequest, RequestOptions.DEFAULT);
        return exists;
    }

    /**
     * ??????index
     * @param index
     * @return
     * @throws IOException
     */
    @Override
    public boolean deleteIndex(String index) throws IOException {
        DeleteIndexRequest deleteIndexRequest = new DeleteIndexRequest(index);
        deleteIndexRequest.timeout(TimeValue.timeValueSeconds(2));
        AcknowledgedResponse deleteIndexResponse = client.indices().delete(deleteIndexRequest,
                RequestOptions.DEFAULT);
        return deleteIndexResponse.isAcknowledged();
    }

    /**
     * ??????index
     * @param index
     * @return
     * @throws IOException
     */
    @Override
    public boolean createIndex(String index) throws IOException {
        if (ConstUtil.TRANS_INDEX_NAME.equals(index)) {
            return createXiguaziIndex();
        }
        // ??????index
        CreateIndexRequest createIndexRequest = new CreateIndexRequest(index);
        CreateIndexResponse createIndexResponse = client.indices().create(createIndexRequest, RequestOptions.DEFAULT);
        return createIndexResponse.isAcknowledged();
    }

    /**
     *
     * ???????????????????????????
     * @param params        ????????????
     * @param indexPage     ????????????
     * @return  ??????????????????
     * @throws IOException
     */
    @Override
    public SearchPageVo searchTrans(TransSearchParam params, Long indexPage) throws IOException {
        Integer pageSize = ConstUtil.TRANS_SEARCH_PAGE_SIZE;
        Long pageFrom = (indexPage - 1) * pageSize;

        //  ???????????????????????????
        List<TransVo> transVOList = new ArrayList<>();

        //  ??????????????????
        SearchRequest searchRequest = new SearchRequest(ConstUtil.TRANS_INDEX_NAME);
        SearchSourceBuilder sourceBuilder = new SearchSourceBuilder();
        sourceBuilder.from(pageFrom.intValue());
        sourceBuilder.size(pageSize);

        //  ??????????????????
        boolean hasClauses = setQueryParam(params, sourceBuilder);

        //  ???????????????
        log.debug("sourceBuilder:\n" + sourceBuilder.toString());

        //  ????????????
        searchRequest.source(sourceBuilder);
        SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);

        //  ????????????
        for (SearchHit documentFields : searchResponse.getHits().getHits()) {
            String sourceAsString = documentFields.getSourceAsString();
            transVOList.add(JSON.parseObject(sourceAsString, TransVo.class));
        }

        // ????????????
        long totalPage = (searchResponse.getHits().getTotalHits().value - 1) / ConstUtil.TRANS_SEARCH_PAGE_SIZE + 1;
        //  ??????indexPage??????totalPage????????????indexPage???totalPage
        indexPage = Math.min(indexPage, totalPage);

        //  ??????????????????
        return new SearchPageVo(transVOList, indexPage, totalPage);
    }

    /**
     * ??????document???elasticsearch???
     * @param transId
     * @return  doc id
     * @throws IOException
     */
    @Override
    public String addDocument(String transId) throws IOException {
        //  ????????????
        TransactionInfo transInfo = transactionInfoMapper.findByTransId(transId);
        CarInfo carInfo = carInfoMapper.findById(transInfo.getCarId());
        TransVo transVO = new TransVo(transInfo, carInfo);

        //  ????????????
        IndexRequest indexRequest = new IndexRequest(ConstUtil.TRANS_INDEX_NAME);
        indexRequest.source(JSON.toJSONString(transVO), XContentType.JSON);

        //  ????????????
        IndexResponse indexResponse = client.index(indexRequest, RequestOptions.DEFAULT);

        return indexResponse.getId();
    }

    /**
     * ???elasticsearch?????????document
     * @param transId
     * @return  status
     * @throws IOException
     */
    @Override
    public Integer deleteDocument(String transId) throws IOException {
        //  ??????document id
        String docId = getDocId(transId);

        //  ?????????????????????
        DeleteRequest deleteRequest = new DeleteRequest(ConstUtil.TRANS_INDEX_NAME, docId);
        DeleteResponse deleteResponse = client.delete(deleteRequest, RequestOptions.DEFAULT);
        return deleteResponse.status().getStatus();
    }

    /**
     * ??????????????????index
     * @return
     * @throws IOException
     */
    private boolean createXiguaziIndex() throws IOException {
        //  ??????trans index
        CreateIndexRequest createIndexRequest = new CreateIndexRequest(ConstUtil.TRANS_INDEX_NAME);
        //  ??????source?????????json?????????
        String source = getXiguaziIndexSource();
        createIndexRequest.source(source, XContentType.JSON);
        //  ????????????
        CreateIndexResponse createIndexResponse = client.indices().create(createIndexRequest, RequestOptions.DEFAULT);

        //  ????????????
        fillXiguaziIndex();

        return createIndexResponse.isAcknowledged();
    }

    /**
     * ?????????????????????????????????index
     */
    private void fillXiguaziIndex() throws IOException {
        //  ????????????????????????
        List<TransVo> releasedTrans = getReleasedTrans();

        //  ????????????document
        BulkRequest bulkRequest = new BulkRequest(ConstUtil.TRANS_INDEX_NAME);
        bulkRequest.timeout(TimeValue.timeValueSeconds(5));
        for (TransVo transVO : releasedTrans) {
            bulkRequest.add(new IndexRequest(ConstUtil.TRANS_INDEX_NAME)
                    .source(JSON.toJSONString(transVO), XContentType.JSON));
        }
        BulkResponse bulkResponse = client.bulk(bulkRequest, RequestOptions.DEFAULT);

        log.debug("bulkResponse.hasFailures:\n" + bulkResponse.hasFailures());
    }

    /**
     * ????????????????????????
     * @return
     */
    private List<TransVo> getReleasedTrans(){
        List<TransactionInfo> releasedTransList = transactionInfoMapper.findByStatus(ConstUtil.TRANS_STATUS_RELEASED);
        List<TransVo> transVOList = new ArrayList<>();
        for (TransactionInfo transactionInfo : releasedTransList) {
            transVOList.add(new TransVo(transactionInfo, carInfoMapper.findById(transactionInfo.getCarId())));
        }
        return transVOList;
    }

    /**
     * ??????index mapping ??????
     * @return
     * @throws IOException
     */
    private String getXiguaziIndexSource() throws IOException {
        File file = ResourceUtils.getFile(ConstUtil.TRANS_INDEX_MAPPING_JSON_FILE_PATH);
        StringBuilder stringBuilder = new StringBuilder();
        FileReader fr = null;
        try {
            fr = new FileReader(file);
            int num;
            while ((num = fr.read()) != -1) {
                stringBuilder.append((char) num);
            }
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            fr.close();
        }
        return stringBuilder.toString();
    }

    /**
     * ??????????????????????????????
     * @param params    ????????????
     *                      -city               ??????
     *                      -make               ??????
     *                      -mileage            ????????????    range
     *                      -enginedisplament   ???????????????   range
     *                      -transmission       ????????? 1=????????????0=?????????
     *                      -price              ??????  range
     * @param sourceBuilder ????????????
     * @return  ????????????????????????????????????true
     */
    private boolean setQueryParam(TransSearchParam params, SearchSourceBuilder sourceBuilder) {
        String city = params.getCity();
        String make = params.getMake();
        String mileage = params.getMileage();
        String enginedisplament = params.getEnginedisplament();
        String transmission = params.getTransmission();
        String price = params.getPrice();

        BoolQueryBuilder boolQueryBuilder = QueryBuilders.boolQuery();

        // ?????? make???city???transmission ????????????
        if(!"all".equals(make)){
            boolQueryBuilder.must(QueryBuilders.matchQuery("car.make", carMakeMap.get(make)));
        }
        if (!"all".equals(city)){
            boolQueryBuilder.must(QueryBuilders.matchQuery("city", cityMap.get(city)));
        }
        if (!"all".equals(transmission)){
            boolQueryBuilder.must(QueryBuilders.matchQuery("car.isAutomaticTransmission",
                    "1".equals(params.getTransmission())));
        }

        //  ?????? mileage???enginedisplament???price ????????????
        mileageRangeQuery(boolQueryBuilder, mileage);
        enginedisplamentRangeQuery(boolQueryBuilder, enginedisplament);
        priceRangeQuery(boolQueryBuilder, price);

        //  ??????????????????
        sourceBuilder.sort(new FieldSortBuilder("createTime").order(SortOrder.DESC));

        //  ??????boolQueryBuilder???????????????????????????
        boolean hasClauses = boolQueryBuilder.hasClauses();
        if(hasClauses){
            sourceBuilder.query(boolQueryBuilder);
        }

        log.debug("boolQueryBuilder.hasClauses():\n" + hasClauses);
        log.debug("boolQueryBuilder:\n"+boolQueryBuilder.toString());

        return hasClauses;
    }

    /**
     * ??????mileage????????????
     * @param boolQueryBuilder
     * @param mileage
     */
    private void mileageRangeQuery(BoolQueryBuilder boolQueryBuilder, String mileage) {
        if(!"all".equals(mileage)){
            switch (mileage){
                case "0":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("car.mileage").gte(0).lte(10000));
                    break;
                case "1":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("car.mileage").gte(10000).lte(30000));
                    break;
                case "2":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("car.mileage").gte(30000).lte(50000));
                    break;
                case "3":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("car.mileage").gte(50000).lte(80000));
                    break;
                case "4":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("car.mileage").gte(80000));
                    break;
            }
        }
    }

    /**
     * ??????enginedisplament????????????
     * @param boolQueryBuilder
     * @param enginedisplament
     */
    private void enginedisplamentRangeQuery(BoolQueryBuilder boolQueryBuilder, String enginedisplament) {
        if(!"all".equals(enginedisplament)){
            switch (enginedisplament){
                case "0":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("car.engineDisplacement").gte(0F).lte(1.0F));
                    break;
                case "1":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("car.engineDisplacement").gte(1.0F).lte(1.6F));
                    break;
                case "2":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("car.engineDisplacement").gte(1.6F).lte(2.0F));
                    break;
                case "3":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("car.engineDisplacement").gte(2.0F).lte(3.0F));
                    break;
                case "4":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("car.engineDisplacement").gte(3.0F).lte(4.0F));
                    break;
                case "5":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("car.engineDisplacement").gte(4.0F));
                    break;
            }
        }
    }

    /**
     * ??????price????????????
     * @param boolQueryBuilder
     * @param price
     */
    private void priceRangeQuery(BoolQueryBuilder boolQueryBuilder, String price) {
        if(!"all".equals(price)){
            switch (price){
                case "0":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("price").gte(0L).lte(30000L));
                    break;
                case "1":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("price").gte(30000L).lte(50000L));
                    break;
                case "2":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("price").gte(50000L).lte(70000L));
                    break;
                case "3":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("price").gte(70000L).lte(90000L));
                    break;
                case "4":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("price").gte(90000L).lte(120000L));
                    break;
                case "5":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("price").gte(120000L).lte(160000L));
                    break;
                case "6":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("price").gte(160000L).lte(200000L));
                    break;
                case "7":
                    boolQueryBuilder.must(QueryBuilders.rangeQuery("price").gte(200000L));
                    break;
            }
        }
    }

    /**
     * ??????document id
     * @param transId
     * @return  document id
     */
    private String getDocId(String transId) throws IOException {
        //  ????????????
        SearchRequest searchRequest = new SearchRequest(ConstUtil.TRANS_INDEX_NAME);
        SearchSourceBuilder searchSourceBuilder = new SearchSourceBuilder();
        searchSourceBuilder.query(QueryBuilders.matchQuery("transId", transId));
        searchRequest.source(searchSourceBuilder);

        //  ????????????
        SearchResponse searchResponse = client.search(searchRequest, RequestOptions.DEFAULT);

        SearchHit[] hits = searchResponse.getHits().getHits();
        if(hits.length == 1){
            return hits[0].getId();
        }

        //  error
        log.error("get document id error:\n" + searchResponse.toString());
        return null;
    }
}
