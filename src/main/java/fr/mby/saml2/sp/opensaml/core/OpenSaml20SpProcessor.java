/**
 * Copyright (C) 2012 RECIA http://www.recia.fr
 * @Author (C) 2012 Maxime Bossard <mxbossard@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * 
 */

package fr.mby.saml2.sp.opensaml.core;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.opensaml.common.SAMLObject;
import org.opensaml.common.SignableSAMLObject;
import org.opensaml.saml2.core.Issuer;
import org.opensaml.saml2.core.RequestAbstractType;
import org.opensaml.saml2.core.StatusResponseType;
import org.opensaml.saml2.encryption.Decrypter;
import org.opensaml.xml.Configuration;
import org.opensaml.xml.encryption.InlineEncryptedKeyResolver;
import org.opensaml.xml.security.SecurityException;
import org.opensaml.xml.security.SecurityHelper;
import org.opensaml.xml.security.credential.BasicCredential;
import org.opensaml.xml.security.credential.Credential;
import org.opensaml.xml.security.keyinfo.StaticKeyInfoCredentialResolver;
import org.opensaml.xml.signature.Signature;
import org.opensaml.xml.signature.impl.SignatureBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import fr.mby.saml2.sp.api.config.IIdpConfig;
import fr.mby.saml2.sp.api.config.ISpConfig;
import fr.mby.saml2.sp.api.core.ISaml20IdpConnector;
import fr.mby.saml2.sp.api.core.ISaml20SpProcessor;
import fr.mby.saml2.sp.api.core.ISaml20Storage;
import fr.mby.saml2.sp.api.exception.SamlProcessingException;
import fr.mby.saml2.sp.api.exception.SamlSecurityException;
import fr.mby.saml2.sp.api.exception.UnsupportedSamlOperation;
import fr.mby.saml2.sp.api.handler.IAuthenticationHandler;
import fr.mby.saml2.sp.api.handler.ISingleLogoutHandler;
import fr.mby.saml2.sp.api.om.IAuthentication;
import fr.mby.saml2.sp.api.om.IIncomingSaml;
import fr.mby.saml2.sp.api.om.IRequestWaitingForResponse;
import fr.mby.saml2.sp.api.query.IQuery;
import fr.mby.saml2.sp.api.query.engine.IQueryProcessor;
import fr.mby.saml2.sp.api.query.engine.IQueryProcessorFactory;
import fr.mby.saml2.sp.impl.helper.SamlHelper;
import fr.mby.saml2.sp.impl.query.QueryAuthnResponse;

/**
 * Basic OpenSaml impl.
 * 
 * @author GIP RECIA 2012 - Maxime BOSSARD.
 * 
 */
@Service
public class OpenSaml20SpProcessor implements ISaml20SpProcessor, InitializingBean {

	/** Logger. */
	private final Logger logger = LoggerFactory.getLogger(OpenSaml20SpProcessor.class);

	/** SP Configuration. */
	private ISpConfig spConfig;

	/** Decrypt responses encrypted assertions with spCertificate. */
	private Decrypter decrypter;

	/** Signature builder. */
	private final SignatureBuilder signatureBuilder = new SignatureBuilder();

	/** SP Signing credentials (spSigningKey + spSigningCertificate). */
	private Credential spSigningCredential;

	/** IdP connectors. */
	private Collection<ISaml20IdpConnector> idpConnectors;

	/** Map of IdP connectors indexed by their entity Id. */
	private final Map<String, ISaml20IdpConnector> idpConnectorsByEntityId = new HashMap<String, ISaml20IdpConnector>();

	/** SAML 2.0 Storage. */
	private ISaml20Storage saml20Storage;

	/** Authentication handler. */
	private IAuthenticationHandler authenticationHandler;

	/** SLO handler. */
	private ISingleLogoutHandler singleLogoutHandler;

	/** Query Processor Factory. */
	private IQueryProcessorFactory queryProcessorFactory;

	@Override
	public IIncomingSaml processSaml20IncomingRequest(final HttpServletRequest request) throws SamlProcessingException,
			UnsupportedSamlOperation {
		IIncomingSaml incomingSaml = null;

		// Build the adapted query processor
		final IQueryProcessor queryProcessor = this.queryProcessorFactory.buildQueryProcessor(this, request);

		// Process the message
		incomingSaml = queryProcessor.processIncomingSamlMessage();

		this.tryAuthenticationPropagation(incomingSaml);

		return incomingSaml;
	}

	@Override
	public ISaml20IdpConnector findSaml20IdpConnectorToUse(final String idpEntityId) {
		return this.idpConnectorsByEntityId.get(idpEntityId);
	}

	@Override
	public ISaml20Storage getSaml20Storage() {
		return this.saml20Storage;
	}

	@Override
	public Decrypter getDecrypter() {
		return this.decrypter;
	}

	@Override
	public ISpConfig getSpConfig() {
		return this.spConfig;
	}

	@Override
	public Signature signSamlObject(final SignableSAMLObject signable) {
		final Signature newSignature = this.buildSignature(true);

		signable.setSignature(newSignature);
		return newSignature;
	}

	@Override
	public void logout(final String sessionIndex) {
		if (this.singleLogoutHandler != null) {
			this.singleLogoutHandler.logout(sessionIndex);
		}
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		Assert.notNull(this.saml20Storage, "The SAML 2.0 Storage wasn't injected !");
		Assert.notNull(this.getSpConfig(), "No SP configuration provided for this SP processor !");
		Assert.notNull(this.queryProcessorFactory, "No QueryProcessorFactory injected !");

		// Retrieve IdP connectors and
		// Register this SP processor in the IdP connectors
		Assert.notEmpty(this.idpConnectors, "No IdP connector injected in the SP processor !");
		for (final ISaml20IdpConnector idpConnector : this.idpConnectors) {
			try {
				idpConnector.registerSaml20SpProcessor(this);
				final IIdpConfig idpConfig = idpConnector.getIdpConfig();
				if (idpConfig != null) {
					this.idpConnectorsByEntityId.put(idpConfig.getIdpEntityId(), idpConnector);
				} else {
					this.logger.warn(
							"No IdP config found while registering an IdPConnector in SPProcessor with id: [{}] !",
							this.getSpConfig().getId());
				}
			} catch (final IllegalAccessError e) {
				// Catch exception thrown by fake IdPs like CAS Fake IdP.
			}
		}

		this.spSigningCredential = SecurityHelper.getSimpleCredential(this.getSpConfig().getSigningCredential()
				.getEntityCertificate(), this.getSpConfig().getSigningKey());
		Assert.notNull(this.spSigningCredential,
				"Unable to build SP signing credentials (signing public + private keys) !");

		this.decrypter = this.buildDecrypter();

		// Register this processor in the Helper
		SamlHelper.registerSpProcessor(this);

		if (this.authenticationHandler == null) {
			this.logger.warn("No Authentication Handler configured !");
		}

		if (this.singleLogoutHandler == null) {
			this.logger.warn("No Single Logout Handler configured !");
		}
	}

	/**
	 * Check if the Response correspond to a request already present in request cache ! Remove the Request Data from
	 * request cache.
	 * 
	 * @param response
	 * @return the original request cannot be null
	 * @throws SamlSecurityException
	 */
	protected IRequestWaitingForResponse checkInResponseToRequest(final String inResponseToId)
			throws SamlSecurityException {
		IRequestWaitingForResponse request = null;

		if (StringUtils.hasText(inResponseToId)) {
			// Get request from cache
			request = this.saml20Storage.removeRequestWaitingForResponse(inResponseToId);
		}

		if (request == null) {
			throw new SamlSecurityException("Response reference a Request which is not (anymore ?) in cache !");
		}

		return request;
	}

	/**
	 * Find the SAML 2.0 IdP Connector to use to process the SAML Object.
	 * 
	 * @param samlObject
	 *            the SAML 2.0 object to process
	 * @return the SAML 2.0 IdP connector attached
	 * @throws SamlProcessingException
	 *             if no IdP connector found
	 */
	protected ISaml20IdpConnector findSaml20IdpConnectorToUse(final SAMLObject samlObject)
			throws SamlProcessingException {
		ISaml20IdpConnector samlConnector = null;

		Assert.notNull(samlObject, "No signable SAML objet provided !");

		if (StatusResponseType.class.isAssignableFrom(samlObject.getClass())) {
			// The SAML object is a Response, so the original request must be in the cache !
			final StatusResponseType samlResponse = (StatusResponseType) samlObject;
			final String originalRequestId = samlResponse.getInResponseTo();

			if (StringUtils.hasText(originalRequestId)) {
				final IRequestWaitingForResponse originalRequestData = this.saml20Storage.findRequestWaitingForResponse(originalRequestId);
				if (originalRequestData != null) {
					samlConnector = originalRequestData.getIdpConnectorBuilder();
				}
			}

		} else if (RequestAbstractType.class.isAssignableFrom(samlObject.getClass())) {
			// Search IdPConnector by Issuer
			final RequestAbstractType samlRequest = (RequestAbstractType) samlObject;

			final Issuer issuer = samlRequest.getIssuer();
			if (issuer != null) {
				final String issuerEntityId = issuer.getValue();
				samlConnector = this.idpConnectorsByEntityId.get(issuerEntityId);
			}

		}

		if (samlConnector == null) {
			throw new SamlProcessingException("Unable to find an IdP Connector to process the SAML request !");
		}

		return samlConnector;
	}

	
	public void setSpConfig(final ISpConfig spConfig) {
		this.spConfig = spConfig;
	}

	/**
	 * Build a decrypter if a private key was provided.
	 * 
	 * @return the decrypter
	 */
	protected Decrypter buildDecrypter() {
		Decrypter decrypter = null;

		final BasicCredential credential = new BasicCredential();
		credential.setPrivateKey(this.getSpConfig().getDecryptionKey());
		decrypter = new Decrypter(null, new StaticKeyInfoCredentialResolver(credential),
				new InlineEncryptedKeyResolver());

		return decrypter;
	}

	/**
	 * Build a SAML2 signature with signing credentials.
	 * 
	 * @return the SAML2 signature.
	 */
	protected Signature buildSignature(final boolean withoutKeyInfo) {
		Signature signature = this.signatureBuilder.buildObject();

		try {
			SecurityHelper.prepareSignatureParams(signature, this.spSigningCredential,
					Configuration.getGlobalSecurityConfiguration(), null);
			signature.setSigningCredential(this.spSigningCredential);

			// FIX MBD: Remove key info which is optional to save request length
			if (withoutKeyInfo) {
				signature.setKeyInfo(null);
			}

		} catch (final SecurityException e) {
			this.logger.error("Error while building signature !", e);
			signature = null;
		}

		return signature;
	}

	/**
	 * Try to propagate authentications to Authentication Handler.
	 * 
	 * @param incomingSaml
	 *            an incoming saml message
	 */
	protected void tryAuthenticationPropagation(final IIncomingSaml incomingSaml) {
		if (incomingSaml != null) {
			final IQuery samlQuery = incomingSaml.getSamlQuery();

			// Propagate authentication to authentication handlers
			if (samlQuery != null && QueryAuthnResponse.class.isAssignableFrom(samlQuery.getClass())) {
				final QueryAuthnResponse queryAuthnResponse = (QueryAuthnResponse) samlQuery;
				if (this.authenticationHandler != null) {
					final List<IAuthentication> authns = queryAuthnResponse.getSamlAuthentications();
					this.authenticationHandler.propagateAuthentications(authns);
				}
			}

		}
	}

	public Collection<ISaml20IdpConnector> getIdpConnectors() {
		return this.idpConnectors;
	}

	public void setIdpConnectors(final Collection<ISaml20IdpConnector> idpConnectors) {
		this.idpConnectors = idpConnectors;
	}

	/**
	 * Setter of samlStorage.
	 * 
	 * @param samlStorage
	 *            the samlStorage to set
	 */
	public void setSaml20Storage(final ISaml20Storage samlStorage) {
		this.saml20Storage = samlStorage;
	}

	/**
	 * Getter of authenticationHandler.
	 * 
	 * @return the authenticationHandler
	 */
	public IAuthenticationHandler getAuthenticationHandler() {
		return this.authenticationHandler;
	}

	/**
	 * Setter of authenticationHandler.
	 * 
	 * @param authenticationHandler
	 *            the authenticationHandler to set
	 */
	public void setAuthenticationHandler(final IAuthenticationHandler authenticationHandler) {
		this.authenticationHandler = authenticationHandler;
	}

	/**
	 * Getter of singleLogoutHandler.
	 * 
	 * @return the singleLogoutHandler
	 */
	public ISingleLogoutHandler getSingleLogoutHandler() {
		return this.singleLogoutHandler;
	}

	/**
	 * Setter of singleLogoutHandler.
	 * 
	 * @param singleLogoutHandler
	 *            the singleLogoutHandler to set
	 */
	public void setSingleLogoutHandler(final ISingleLogoutHandler singleLogoutHandler) {
		this.singleLogoutHandler = singleLogoutHandler;
	}

	/**
	 * Getter of queryProcessorFactory.
	 * 
	 * @return the queryProcessorFactory
	 */
	public IQueryProcessorFactory getQueryProcessorFactory() {
		return this.queryProcessorFactory;
	}

	/**
	 * Setter of queryProcessorFactory.
	 * 
	 * @param queryProcessorFactory
	 *            the queryProcessorFactory to set
	 */
	public void setQueryProcessorFactory(final IQueryProcessorFactory queryProcessorFactory) {
		this.queryProcessorFactory = queryProcessorFactory;
	}

}
