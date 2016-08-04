package com.enigmabridge.log.distributor.db.dao;

import com.enigmabridge.log.distributor.db.model.Client;
import org.springframework.data.repository.CrudRepository;

/**
 * A DAO for the entity User is simply created by extending the CrudRepository
 * interface provided by spring. The following methods are some of the ones
 * available from such interface: save, delete, deleteAll, findOne and findAll.
 * The magic is that such methods must not be implemented, and moreover it is
 * possible create new query methods working only by defining their signature!
 */
public interface ClientDao extends CrudRepository<Client, Integer> {
    /**
     * Return the user having the passed clientId or null if no user is found.
     *
     * @param clientId the client email.
     */
    public Client findByClientId(String clientId);
}
