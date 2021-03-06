/*
 * JBoss, Home of Professional Open Source
 * Copyright 2013 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 */
package org.jboss.planet.service;

import org.jasig.cas.client.util.AbstractCasFilter;
import org.jasig.cas.client.validation.Assertion;
import org.jboss.planet.model.*;
import org.jboss.planet.model.RemoteFeed.FeedStatus;
import org.jboss.planet.security.CRUDOperationType;
import org.jboss.planet.security.PermissionCRUDException;
import org.jboss.planet.security.UserNotLoggedInException;
import org.jboss.planet.service.qualifier.Updated;

import javax.annotation.PostConstruct;
import javax.enterprise.context.SessionScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.inject.Named;
import javax.persistence.EntityManager;
import javax.persistence.NonUniqueResultException;
import javax.servlet.http.HttpServletRequest;
import java.io.Serializable;
import java.security.Principal;
import java.util.ArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Security service which keeps authenticated user
 *
 * @author Libor Krzyzanek
 */
@SessionScoped
@Named("security")
public class SecurityService implements Serializable {

	private static final long serialVersionUID = -3254055864292171599L;

	@Inject
	private Logger log;

	@Inject
	private EntityManager em;

	@Inject
	private HttpServletRequest request;

	private SecurityUser currentUser;

	@Inject
	private UserService userService;

	private final String ANONYMOUS_USERNAME = "anonymous";

	@PostConstruct
	public void init() {
		final Assertion assertion = getUserFromSession();
		if (assertion == null) {
			setCurrentUser(null);
		} else {
			setCurrentUser(assertion.getPrincipal());
		}
	}

	public Assertion getUserFromSession() {
		return (Assertion) request.getSession().getAttribute(AbstractCasFilter.CONST_CAS_ASSERTION);
	}

	public void observeUserChange(@Observes @Updated SecurityUser updatedUser) {
		log.log(Level.FINE, "Permissions changed for user: {0}", updatedUser);
		if (updatedUser.equals(currentUser)) {
			setCurrentUser(updatedUser);
		}
	}

	/**
	 * Get security user if any exist
	 *
	 * @param username
	 * @return user with defined roles (if any)
	 */
	private SecurityUser getSecurityUser(String username) {
		try {
			SecurityUser user = userService.getUser(username);
			if (user == null) {
				SecurityUser noRolesUser = new SecurityUser();
				noRolesUser.setExternalId(username);
				noRolesUser.setMappings(new ArrayList<SecurityMapping>());

				user = userService.create(noRolesUser);
			}
			return user;
		} catch (NonUniqueResultException e) {
			throw new RuntimeException(
					"Data integrity exception. Roles for user " + username + " has not unique entry", e);
		}
	}

	public void logout() {
		setCurrentUser(null);
	}

	public boolean isAnonymous(Principal currentUser) {
		return currentUser == null || currentUser.getName().equals(ANONYMOUS_USERNAME);
	}

	public boolean hasRole(SecurityUser user, FeedsSecurityRole role) {
		if (isAnonymous(user)) {
			return false;
		}
		for (SecurityMapping mapping : user.getMappings()) {
			if (role.equals(mapping.getRole())) {
				return true;
			}
		}
		return false;
	}

	public boolean isAdmin(SecurityUser user) {
		return hasRole(user, FeedsSecurityRole.ADMIN);
	}

	public boolean isGroupAdmin(SecurityUser user) {
		if (isAdmin(user)) {
			return true;
		}
		return hasRole(user, FeedsSecurityRole.GROUP_ADMIN);
	}

	/**
	 * Check if user has permission
	 *
	 * @param entity
	 * @param operation
	 * @throws UserNotLoggedInException if user is not logged in
	 * @throws PermissionCRUDException  if user doesn't have sufficient permissions
	 */
	public void checkPermission(Object entity, CRUDOperationType operation) throws UserNotLoggedInException,
			PermissionCRUDException {
		if (isAnonymous(currentUser)) {
			throw new UserNotLoggedInException();
		}
		if (!hasPermission(currentUser, entity, operation)) {
			throw new PermissionCRUDException(currentUser, entity, operation);
		}
	}

	/**
	 * Helper method to determine if current user can delete entity
	 *
	 * @param entity
	 * @return
	 */
	public boolean canUserDelete(Object entity) {
		return hasPermission(currentUser, entity, CRUDOperationType.DELETE);
	}

	public boolean hasPermission(SecurityUser user, Object entity, CRUDOperationType operation) {
		if (isAnonymous(user)) {
			return false;
		}

		if (entity instanceof Post) {
			Post post = (Post) entity;
			// There is no granularity on Post level so same logic for feed.
			return hasPermission(user, post.getFeed(), operation);
		}

		if (entity instanceof RemoteFeed) {
			return hasPermission(user, (RemoteFeed) entity, operation);
		}

		if (entity instanceof FeedGroup) {
			FeedGroup group = (FeedGroup) entity;
			for (SecurityMapping mapping : user.getMappings()) {
				if (FeedsSecurityRole.ADMIN.equals(mapping.getRole())) {
					return true;
				}
				if (FeedsSecurityRole.GROUP_ADMIN.equals(mapping.getRole())
						&& mapping.getIdForRole().equals(group.getId())) {
					return true;
				}
			}
			return false;
		}

		if (entity instanceof SecurityMapping) {
			SecurityMapping mappingToUpdate = (SecurityMapping) entity;
			for (SecurityMapping currentUserMapping : user.getMappings()) {
				if (FeedsSecurityRole.ADMIN.equals(currentUserMapping.getRole())) {
					return true;
				}
				// Admin of same mapping has permissions to manage it
				if (currentUserMapping.getId().equals(mappingToUpdate.getId())) {
					return true;
				}
				if (FeedsSecurityRole.GROUP_ADMIN.equals(currentUserMapping.getRole())
						&& FeedsSecurityRole.FEED_ADMIN.equals(mappingToUpdate.getRole())) {
					// Group admin is updating feed admins
					RemoteFeed feed = em.getReference(RemoteFeed.class, mappingToUpdate.getIdForRole());
					if (feed.getGroup().getId().equals(currentUserMapping.getIdForRole())) {
						return true;
					}
				}
			}
			return false;
		}

		if (entity instanceof Configuration) {
			for (SecurityMapping mapping : user.getMappings()) {
				if (FeedsSecurityRole.ADMIN.equals(mapping.getRole())) {
					return true;
				}
			}
			return false;
		}

		if (entity instanceof TagsGroup) {
			for (SecurityMapping mapping : user.getMappings()) {
				if (FeedsSecurityRole.ADMIN.equals(mapping.getRole())) {
					return true;
				}
			}
			return false;
		}

		return false;
	}

	public boolean hasPermission(SecurityUser user, RemoteFeed feed, CRUDOperationType operation) {
		if (CRUDOperationType.CREATE.equals(operation) && feed.getStatus().compareTo(FeedStatus.PROPOSED) == 0) {
			// Proposing a new feed
			return true;
		}

		for (SecurityMapping mapping : user.getMappings()) {
			if (FeedsSecurityRole.ADMIN.equals(mapping.getRole())) {
				return true;
			}
			if (FeedsSecurityRole.FEED_ADMIN.equals(mapping.getRole())
					&& mapping.getIdForRole().equals(feed.getId())) {
				return true;
			}
			if (FeedsSecurityRole.GROUP_ADMIN.equals(mapping.getRole())
					&& mapping.getIdForRole().equals(feed.getGroup().getId())) {
				return true;
			}
		}
		return false;

	}

	public SecurityUser getCurrentUser() {
		return currentUser;
	}

	public void setCurrentUser(Principal user) {
		if (user != null) {
			this.currentUser = getSecurityUser(user.getName());
		} else {
			SecurityUser noRolesUser = new SecurityUser();
			noRolesUser.setExternalId("anonymous");
			noRolesUser.setMappings(new ArrayList<SecurityMapping>());
			this.currentUser = noRolesUser;
		}
	}

}
