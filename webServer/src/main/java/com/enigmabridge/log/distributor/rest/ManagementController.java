package com.enigmabridge.log.distributor.rest;

import com.enigmabridge.log.distributor.LogConstants;
import com.enigmabridge.log.distributor.Server;
import com.enigmabridge.log.distributor.Stats;
import com.enigmabridge.log.distributor.api.ApiConfig;
import com.enigmabridge.log.distributor.api.requests.AddClientsRequest;
import com.enigmabridge.log.distributor.api.requests.ClientReq;
import com.enigmabridge.log.distributor.api.response.ConfigResponse;
import com.enigmabridge.log.distributor.api.response.ErrorResponse;
import com.enigmabridge.log.distributor.api.response.GeneralResponse;
import com.enigmabridge.log.distributor.api.response.ResultResponse;
import com.enigmabridge.log.distributor.db.ClientBuilder;
import com.enigmabridge.log.distributor.db.DbHelper;
import com.enigmabridge.log.distributor.db.dao.ClientDao;
import com.enigmabridge.log.distributor.db.dao.UserObjectDao;
import com.enigmabridge.log.distributor.db.model.*;
import com.enigmabridge.log.distributor.forwarder.Router;
import com.enigmabridge.log.distributor.utils.DomainUtils;
import com.enigmabridge.log.distributor.utils.Utils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;
import java.util.*;

/**
 * Create new API Key calls, common administration stuff.
 *
 * Created by dusanklinec on 01.08.16.
 */
@RestController
@PreAuthorize("hasAuthority('"+ ApiConfig.MANAGEMENT_ROLE+"')")
@DependsOn(ApiConfig.YAML_CONFIG)
public class ManagementController {
    private final static Logger LOG = LoggerFactory.getLogger(ManagementController.class);

    @Autowired
    private ClientDao clientDao;

    @Autowired
    private UserObjectDao userObjectDao;

    @Autowired
    private ClientBuilder clientBuilder;

    @Autowired
    private Router router;

    @Autowired
    private Server server;

    @Autowired
    private DbHelper dbHelper;

    @Autowired
    private LogicManager logic;

    @Autowired
    private Stats stats;

    @Autowired
    private EntityManager em;

    /**
     * Reloads current routing configuration.
     * Restarts HTTP handlers.
     *
     * @return client response
     */
    @Transactional
    @RequestMapping(value = ApiConfig.API_PATH + "/reload", method = RequestMethod.GET)
    public GeneralResponse reload(
            @RequestParam(required = false, value="lazy", defaultValue = "true") boolean lazy
    ){
        router.reload(clientDao.findAll(), lazy);
        stats.incReloads();
        return new ResultResponse();
    }

    /**
     * Flushes all localy cached buffers to listeners.
     *
     * @return client response
     */
    @Transactional
    @RequestMapping(value = ApiConfig.API_PATH + "/flush", method = RequestMethod.GET)
    public GeneralResponse flush() {
        router.flush();
        stats.incFlushes();
        return new ResultResponse();
    }

    /**
     * Dumps the whole client configuration.
     * @return client response
     */
    @Transactional
    @RequestMapping(value = ApiConfig.API_PATH + "/client/list", method = RequestMethod.GET)
    public GeneralResponse dumpConfiguration(){
        final ConfigResponse resp = new ConfigResponse();

        // Load clients
        final List<Client> clientList = new LinkedList<>();
        dbHelper.findAllClients().forEach(clientList::add);

        // Load domains
        final List<Domain> domainList = new LinkedList<>();
        dbHelper.findAllDomains().forEach(domainList::add);

        // Load hosts
        final List<EBHost> hostList = new LinkedList<>();
        dbHelper.findAllHosts().forEach(hostList::add);

        resp.setClients(clientList);
        resp.setDomains(domainList);
        resp.setHosts(hostList);
        return resp;
    }

    /**
     * Dumps local statistics.
     *
     * @return client response
     */
    @Transactional
    @RequestMapping(value = ApiConfig.API_PATH + "/stats", method = RequestMethod.GET)
    public Stats dumpStats(){
        return stats;
    }

    /**
     * Deletes all clients with given client ID on all domains.
     *
     * @param clientId client ID records to delete
     * @return response
     */
    @Transactional
    @RequestMapping(value = ApiConfig.API_PATH + "/client/{clientId}", method = RequestMethod.DELETE)
    public GeneralResponse deleteClient(@PathVariable(value = "clientId") String clientId){
        try {
            clientDao.deleteByClientId(clientId);
            router.reload(clientDao.findAll());

            return new ResultResponse();
        } catch(Exception e){
            LOG.error("Exception when deleting the client", e);
            return new ErrorResponse("Exception");
        }
    }

    /**
     * Deletes all clients with given client ID on given domain.
     *
     * @param clientId client ID records to delete
     * @return response
     */
    @Transactional
    @RequestMapping(value = ApiConfig.API_PATH + "/client/{clientId}/{domain}", method = RequestMethod.DELETE)
    public GeneralResponse deleteClientOnDomain(
            @PathVariable(value = "clientId") String clientId,
            @PathVariable(value = "domain") String domain
    ){
        try {
            domain = DomainUtils.sanitize(domain);
            dbHelper.deleteClientsByClientIdAndDomain(clientId, domain);
            router.reload(clientDao.findAll());

            return new ResultResponse();
        } catch(Exception e){
            LOG.error("Exception when deleting the client", e);
            return new ErrorResponse("Exception");
        }
    }

    /**
     * Sets the client record to the database.
     * If client with same client id exists, it is removed and replaced with this new record.
     * Dump can be imported back to the database in this way.
     *
     * @param addClientReq client records to add.
     * @return response
     */
    @Transactional
    @RequestMapping(value = ApiConfig.API_PATH + "/client/add", method = RequestMethod.POST)
    public GeneralResponse addClient(@RequestBody AddClientsRequest addClientReq){
        final List<ClientReq> clientReqs = addClientReq.getClients();
        for (ClientReq clientReq : clientReqs) {
            final String clientId = clientReq.getClientId();
            final String domain = clientReq.getDomain();

            try {
                final Client dbClient = clientBuilder.build(clientReq);
                if (domain != null){
                    dbHelper.deleteClientsByClientIdAndDomain(clientId, domain);
                }

                clientDao.save(dbClient);
                router.reload(clientDao.findAll());

            } catch(Exception e){
                LOG.error("Exception in adding client", e);
                return new ErrorResponse("Exception");
            }
        }

        return new ResultResponse();
    }

    /**
     * Adds new UO to existing client record.
     *
     * @param clientId client id to add object to
     * @param object object to add
     * @return response
     */
    @Transactional
    @RequestMapping(value = ApiConfig.API_PATH + "/client/addObject/{clientId}/{domain}", method = RequestMethod.POST)
    public GeneralResponse addObject(@PathVariable(value = "clientId") String clientId,
                                     @PathVariable(value = "domain") String domain,
                                     @RequestBody UserObject object
    ){
        try {
            domain = DomainUtils.sanitize(domain);
            final Client client = dbHelper.findByClientIdAndDomain(clientId, domain);
            if (client == null){
                return new ErrorResponse("Client not found");
            }

            // Already added?
            final List<UserObject> objects = client.getObjects();
            for (UserObject userObject : objects) {
                if (userObject.equals(object)){
                    return new ErrorResponse("Object already added");
                }
            }

            // Add
            client.addObject(object);
            clientDao.save(client);
            router.reload(clientDao.findAll());

        } catch(Exception e){
            LOG.error("Exception when adding object", e);
            return new ErrorResponse("Exception");
        }

        return new ResultResponse();
    }

    /**
     * TODO: change to domain oriented approach. This record wont be needed, server+domain will specify it.
     * Adds object to client that has same api key in records.
     * This method is used when new UO was created but caller has no record which client it belongs to.
     * Server goes through user object database and tries to find a client which has the same api key.
     * The object is added to the client if only one client is using the same api key.
     * TODO: If there are conflicts (more clients), object is added to that one which uses "catcher" UO =
     * TODO: user object record with API key, uotype=-1, uoid=-1.
     *
     * @param object object to add
     * @return response
     */
    @Transactional
    @RequestMapping(value = ApiConfig.API_PATH + "/client/addObject", method = RequestMethod.POST)
    public GeneralResponse addObjectGuess(@RequestBody UserObject object){
        try {
            // Fetch all clients which have this api key.
            final TypedQuery<Client> query = em.createQuery("SELECT uo.client" +
                    " FROM UserObject uo" +
                    " WHERE uo.apiKey = :apiKey GROUP BY uo.client", Client.class);
            query.setParameter("apiKey", object.getApiKey());
            final List<Client> matches = query.getResultList();

            if (matches.size() > 1){
                return new ErrorResponse("API key is used by more than 1 client, cannot add");
            }

            final Client client = matches.get(0);

            // Duplicate detection
            for (UserObject userObject : client.getObjects()) {
                if (userObject.equals(object)){
                    return new ErrorResponse("Object already added");
                }
            }

            client.addObject(object);
            clientDao.save(client);
            router.reload(clientDao.findAll());

        } catch(Exception e){
            LOG.error("Exception when adding object", e);
            return new ErrorResponse("Exception");
        }

        return new ResultResponse();
    }

    /**
     * Accepts configuration from the business server.
     *
     * {"clients":[{"clientid":"TEST","enabled":2,"clientapis":{"API_TEST":{"apikey":"API_TEST","use":[1152,1153,21896,21913,21930,30634,4660,30651,21828,21829,30668,30292,21845,34901,13398,30685,1120,1121,1122,1123,1124,1125,21862,1126,1127,1128,1129,1130,1131,1132,1133,1134,1135,1136,1137,1138,1139,1140,1141,39030,1142,1143,1144,1145,1146,1147,1148,1149,1150,1151],"enabled":2,"manage":[], "domain":"domainname"}}}]}
     *
     * @param jsonStr json string to process
     * @return response
     */
    @Transactional
    @RequestMapping(value = ApiConfig.API_PATH + "/client/business/configure", method = RequestMethod.POST)
    public GeneralResponse configureClient(@RequestBody String jsonStr){
        final String FIELD_CLIENTS = "clients";
        final String FIELD_DOMAIN = "domain";
        final String FIELD_CLIENT_ID = "clientid";
        final String FIELD_CLIENT_API = "clientapis";
        final String FIELD_API_KEY = "apikey";
        final String FIELD_USE = "use";
        boolean reload = false;

        try {
            final JSONObject json = new JSONObject(jsonStr);
            final JSONArray clientsArray = json.getJSONArray(FIELD_CLIENTS);
            final int len = clientsArray.length();
            for(int idx=0; idx<len; idx++){
                final JSONObject cl = clientsArray.getJSONObject(idx);
                final JSONObject apis = cl.getJSONObject(FIELD_CLIENT_API);

                final Iterator<String> keyIt = apis.keys();
                for(;keyIt.hasNext();){
                    final String key = keyIt.next();
                    final JSONObject apiObj = apis.getJSONObject(key);

                    final String domain = DomainUtils.sanitize(Utils.getAsString(apiObj, FIELD_DOMAIN).orElse(LogConstants.DEFAULT_DOMAIN));
                    final String apiKey = apiObj.getString(FIELD_API_KEY);

                    final Client clientModel = new Client();
                    clientModel.setDomain(dbHelper.getDomain(domain));
                    clientModel.setClientId(cl.getString(FIELD_CLIENT_ID));

                    // Exists in database? If yes, keep stats config.
                    final Client clientFromDb = dbHelper.findByClientIdAndDomain(clientModel.getClientId(), clientModel.getDomain());

                    // Build set of currently existing UOs - for set operations.
                    final Set<UserObject> uoExisting = new HashSet<>();
                    if (clientFromDb != null){
                        uoExisting.addAll(clientFromDb.getObjects());
                    }

                    // UOlist to add & remove.
                    final List<UserObject> uos2add = new LinkedList<>();
                    final Set<UserObject> uos2del = new HashSet<>(uoExisting);

                    final JSONArray useArr = apiObj.getJSONArray(FIELD_USE);
                    for(int idx2=0, ln2=useArr.length(); idx2<ln2; idx2++){
                        final UserObject uo = new UserObject();
                        uo.setApiKey(apiKey);
                        uo.setClient(clientModel);
                        uo.setUoId(useArr.getInt(idx2));

                        uos2del.remove(uo);
                        if (!uoExisting.contains(uo)){
                            uos2add.add(uo);
                        }
                    }

                    if (clientFromDb != null){
                        // Keep configuration of the existing client record. Delete all user objects - will be replaced by
                        // new user object list. Only domain is updated.
                        if (!uos2del.isEmpty()){
                            userObjectDao.delete(uos2del);
                            clientFromDb.getObjects().removeAll(uos2del);
                        }

                        if (!uos2add.isEmpty()){
                            clientFromDb.addObjects(uos2add);
                        }

                        clientFromDb.setDomain(clientModel.getDomain());
                        clientDao.save(clientFromDb);
                        
                    } else {
                        clientModel.addObjects(uos2add);
                        clientDao.save(clientModel);
                    }

                    reload = true;
                }
            }

        } catch(Exception e){
            LOG.error("Exception in parsing input data", e);
            return new ErrorResponse("Exception in parsing input data");
        }

        if (reload) {
            router.reload(clientDao.findAll());
        }

        return new ResultResponse();
    }

    /**
     * Accepts configuration from the site server.
     *
     * http://site2.enigmabridge.com:12000/1.0/testAPI/GetAllAPIKeys/sdfgsgf
     * {"function":"GetAllAPIKeys","result":{"API_TEST":{"use":[16,17,...,39030],"domain":"DEVELOPMENT","manage":[]}},"status":"9000","statusdetail":"success (ok)","version":"1.0"}
     *
     * @param jsonStr json string to process
     * @return response
     */
    @Transactional
    @RequestMapping(value = ApiConfig.API_PATH + "/client/site/configure", method = RequestMethod.POST)
    public GeneralResponse processSiteDump(@RequestBody String jsonStr){
        return logic.processSiteDump(jsonStr);
    }

    /**
     * Removes object from existing client record.
     *
     * @param clientId client to remove object from
     * @param object object to remove
     * @return response
     */
    @Transactional
    @RequestMapping(value = ApiConfig.API_PATH + "/client/removeObject/{clientId}/{domain}", method = RequestMethod.POST)
    public GeneralResponse removeObject(@PathVariable(value = "clientId") String clientId,
                                        @PathVariable(value = "domain") String domain,
                                        @RequestBody UserObject object
    ){
        try {
            domain = DomainUtils.sanitize(domain);
            final Client client = dbHelper.findByClientIdAndDomain(clientId, domain);
            if (client == null){
                return new ErrorResponse("Client not found");
            }

            // Already added?
            final List<UserObject> objects = client.getObjects();
            final Iterator<UserObject> iterator = objects.iterator();
            boolean modified = false;

            while(iterator.hasNext()){
                final UserObject cur = iterator.next();
                if (cur.equals(object)){
                    iterator.remove();
                    userObjectDao.delete(cur);
                    modified = true;
                    break;
                }
            }

            if (modified){
                router.reload(clientDao.findAll());
                return new ResultResponse();

            } else {
                return new ErrorResponse("Object not found");
            }

        } catch(Exception e){
            LOG.error("Exception when removing object", e);
            return new ErrorResponse("Exception");
        }
    }

    /**
     * Updates existing client statistics forwarding configuration, preserving object list intact.
     *
     * @param clientId client id to update config to
     * @param newClient new client configuration
     * @return response
     */
    @Transactional
    @RequestMapping(value = ApiConfig.API_PATH + "/client/config/{clientId}/{domain}", method = RequestMethod.POST)
    public GeneralResponse updateStatsConfig(@PathVariable(value = "clientId") String clientId,
                                             @PathVariable(value = "domain") String domain,
                                             @RequestBody ClientReq newClient
    ){
        try {
            domain = DomainUtils.sanitize(domain);
            final Client client = dbHelper.findByClientIdAndDomain(clientId, domain);
            if (client == null){
                return new ErrorResponse("Client not found");
            }

            if (newClient.isLogstashConfigSet()){
                final LogstashConfig oldConfig = client.getLogstashConfig();
                if (oldConfig != null){
                    client.setLogstashConfig(null);
                    em.remove(oldConfig);
                }
                client.setLogstashConfig(clientBuilder.build(newClient.getLogstashConfig()));
            }

            if (newClient.isSplunkConfigSet()){
                final SplunkConfig oldConfig = client.getSplunkConfig();
                if (oldConfig != null){
                    client.setSplunkConfig(null);
                    em.remove(oldConfig);
                }
                client.setSplunkConfig(clientBuilder.build(newClient.getSplunkConfig()));
            }

            clientDao.save(client);
            router.reload(clientDao.findAll());
            return new ResultResponse();

        } catch(Exception e){
            LOG.error("Exception when configuring stats settings", e);
            return new ErrorResponse("Exception");
        }
    }

}
