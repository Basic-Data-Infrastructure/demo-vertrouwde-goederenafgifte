---
# SPDX-FileCopyrightText: 2024 Jomco B.V.
# SPDX-FileCopyrightText: 2024 Topsector Logistiek
# SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
# SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
#
# SPDX-License-Identifier: AGPL-3.0-or-later

title: Bevindingen DIL VGU Demo Fase 3
date: TODO
author:
  - Joost Diepenmaat <joost@jomco.nl>
  - Remco van 't Veer <remco@jomco.nl>
lang: nl
toc: false
---

Dit document beschrijft onze bevindingen bij de implementatie van de DIL-demo fase 3. We benoemen hier de pijnpunten, zodat deze als input kunnen dienen voor verbeteringen aan architectuur, componenten en documentatie. 

# Focus fase 3: gate-out events

In fase 1 blijft de oorspronkelijke vervoerder direct verantwoordelijk voor het plannen van een chauffeur; als deze de rit wil uitbesteden aan een externe partij, kan dat alleen door in het TMS van de vervoerder de externe chauffeur en wagen in te plannen. 

In fase 2 willen we demonstreren hoe een opdracht uitbesteed kan worden aan een derde vervoerder die in het BDI netwerk is opgenomen, en na uitbesteding zijn planning en bijbehorende autorisaties kan beheren via het eigen TMS. We willen tegelijkertijd de afhankelijkheid van online services zoveel mogelijk beperken.

In fase 3 willen we TODO TODO

Uitgangspunt voor de architectuur in fase 3 is dat we zoveel mogelijk gebruik maken van bestaande iSHARE-protocollen, -componenten en toepassing van het "data bij de bron"-principe.

# Bevindingen

## Inrichting topics en events

We hebben gekozen voor een topic per transportopdracht omdat dit de meest voor de hand liggende keuze is.  Alle events met betrekking tot de opdracht worden gepubliceerd op dat topic.

## Eigenaarschap van topics en events

Initiator van de transportopdracht is eigenaar van het topic; in  deze demo is dat de verlader.  Producent van een event is eigenaar van het event; het DC.

## Toegang tot topic

Voor Pulsar is een speciale autorisatie-module gemaakt om naar aanleiding van delegation-evidence in het AR van de topic-eigenaar toegang te verlenen tot het topic.

Zie ook [Topsector-Logistiek/Apache-Pulsar-Auth-Plugin](https://github.com/Topsector-Logistiek/Apache-Pulsar-Auth-Plugin/) en met name [org.bdinetwork.pulsarishare.authorization](https://github.com/Topsector-Logistiek/Apache-Pulsar-Auth-Plugin/tree/main/pulsarishare/src/main/java/org/bdinetwork/pulsarishare/authorization).

## Delegation-Evidence bij uitbesteding aan derde

Toegang tot het topic voor een transporteur waaraan uitbesteed is, is net zo omslachtig als in fase 2, hiervoor worden extra policies en HTTP headers nodig om Pulsar duidelijk te maken namens wie zij toegang tot het topic wensen te krijgen.

## Data bij de bron

Om te zorgen dat events zo min mogelijk data bevatten wordt alleen een URL gepubliceerd.  Dat betekent dat de partij die een event publiceert ook een endpoint beschikbaar moet stellen om deze te downloaden. Daarnaast moet een iSHARE token-endpoint aangeboden worden ter authenticatie voor de download.

Wat hier lastig is, is dat er geen mechanisme is om te achterhalen waar het token endpoint voor een gegeven URL te vinden is.  Hiervoor hebben we gekozen voor een `WWW-Authenticate` header in het `401 Unauthorized` response waarin beschreven staat waar het  endpoint is en wat de EORI van de server is (nodig om een  `client_assertion` te kunnen opstellen).

# Conclusie

TODO

# Referenties

TODO
