---
# SPDX-FileCopyrightText: 2025 Jomco B.V.
# SPDX-FileCopyrightText: 2025 Topsector Logistiek
# SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
#
# SPDX-License-Identifier: AGPL-3.0-or-later

title: WWW-Authenticate challenge for iSHARE access token discovery
date: TODO
author:
  - Remco van 't Veer <remco@jomco.nl>
lang: en
toc: 1
---

<!--

RFC style guide:
https://www.ietf.org/archive/id/draft-flanagan-7322bis-07.html
  
iSHARE change management process:
https://changes.ishare.eu/change-management-process
  
iSHARE RFC template:
## Background and rationale

Describe the current situation and the complications that have led to the creation of this RFC.

## Proposed change: purpose

Describe the purpose of the RFC. What will it solve and what use cases will benefit from the change.

## Proposed change: considerations and requirements

Describe all relevant considerations and requirements that should be taken into account when performing the impact analysis on the RFC.

-->

# Abstract

This Request for Comments describes using a WWW-Authenticate response header field for access token endpoint discovery. A calling party may only know the location of the resource but not where to get a token to authenticate for access. This is a proposal to communicate the URL using the authentication challenge.

# Introduction

Using the iSHARE protocol to handle authentication for a resource requires a service consumer to know where to find a token endpoint to request an access token.  Currently the specification does not specify how to derive the location of this endpoint for a given resource.

Following the HTTP protocol, unauthorized access from a service consumer should be responded to with "a 401 (Unauthorized) response and a server MUST send a WWW-Authenticate header field containing at least one challenge".  However, the iSHARE protocol does not specify what the content of this header should be.

Since iSHARE is related to OAuth 2.0, it can build upon RFC 6749 "The OAuth 2.0 Authorization Framework" and RFC 6750 "The OAuth 2.0 Authorization Framework: Bearer Token Usage" to provide more information about the access token endpoint location and party ID of the service provider.

# Unauthenticated Request

When receiving a request for a protected resource, a service provider MUST respond with a 401 (Unauthorized) response and provide at least one authentication challenge with authentication scheme `Bearer` and an authentication parameter `scope` containing `iSHARE` using the "WWW-Authenticate" response header.

```
WWW-Authenticate: Bearer scope="iSHARE"
```

This signals to the service consumer that an iSHARE access token endpoint MUST be used to obtain a bearer token for access.  An access token endpoint MAY be available at "/connect/token" relative to the location of the resource.

To let the service consumer know the location of the endpoint, the challenge SHOULD include the `server_id` containing the party ID of the service provider and `server_access_token_endpoint` for the URL to the access token endpoint.  The URL MAY be relative to the URL of the resource being accessed.

```
WWW-Authenticate: Bearer scope="iSHARE" server_id="EU.EORI.1234" server_access_token_endpoint="https://example.com/foo/connect/token"
```

This provides the service consumer with enough information to acquire an access token.

## Multiple associations

TODO

## Token reuse in a "realm"

TODO

# Security Considerations

TODO

# References

## Normative References

### RFC9110

Fielding, R., Nottingham, M., and J. Reschke, "HTTP Semantics", STD
97, RFC 9110, DOI 10.17487/RFC9110, February 2022,
<https://www.rfc-editor.org/rfc/rfc9110>.

### RFC6750

Jones, M. and D. Hardt, "The OAuth 2.0 Authorization Framework: Bearer
Token Usage", RFC 6750, DOI 10.17487/RFC6750, October 2012,
<https://www.rfc-editor.org/info/rfc6750>.

### RFC6749

Hardt, D., Ed., "The OAuth 2.0 Authorization Framework", RFC 6749, DOI
10.17487/RFC6749, October 2012,
<https://www.rfc-editor.org/info/rfc6749>.
