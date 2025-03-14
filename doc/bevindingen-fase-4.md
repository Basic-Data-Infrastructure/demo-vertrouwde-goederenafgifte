---
# SPDX-FileCopyrightText: 2025 Jomco B.V.
# SPDX-FileCopyrightText: 2025 Topsector Logistiek
# SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
#
# SPDX-License-Identifier: AGPL-3.0-or-later

title: Bevindingen DIL VGU Demo Fase 4
date: 2025-03-12
author:
  - Remco van 't Veer <remco@jomco.nl>
lang: nl
toc: false
---

Dit document rapporteert de bevindingen die voortkomen uit de implementatie van de VGU-demo in fase 4. De geïdentificeerde pijnpunten worden hier uiteengezet, met als doel deze te gebruiken als input voor toekomstige verbeteringen aan de architectuur, componenten en bijbehorende documentatie.

Relevante BDI Building Blocks:

- Autorisatie
- Authenticatie
- Discovery
- Event Pub/Sub
- Webhooks


# Focus fase 4: DCSA webhook events

In fase 4 beoogt men te demonstreren dat zowel een verlader als een vervoerder een update ontvangen wanneer de lading (container) een terminal binnenkomt, op een schip wordt geladen en het schip de haven verlaat.

Het uitgangspunt voor de architectuur in fase 4 is dat DCSA-events van de haven worden ontvangen via webhooks, waarna deze worden vertaald naar EPCIS-events en opnieuw worden gepubliceerd via de reeds gebruikte event broker. De events worden aangeboden door een Portbase-testsysteem en kunnen worden gesimuleerd middels een test-API.

# Bevindingen

## Inrichting abonnementen en events

Initieel werd aan Portbase één enkele webhook aangeboden, waarnaar alle events uit het testsysteem werden verzonden. In een latere implementatie werd een model geïntroduceerd waarbij men zich kan abonneren op *equipment* (container) en *vessel* (schip waarop de container is geladen).

De reden voor deze opsplitsing is dat een event over het vertrek van het schip (*transport departed*) niet per container kan worden uitgedrukt in het DCSA-model.  De container is namelijk geen onderdeel van dit event in het model.  Dit zou aan de zijde van Portbase opgelost kunnen worden, waarbij zij zouden registreren op welk schip een container zich bevindt en dit event doorgeven via een container-webhook-abonnement.  Echter, deze administratieve taak is verschoven naar de consument, in dit geval de demo-applicatie.

Het gevolg hiervan is dat bij ontvangst van een event over het laden van een container, de meegegeven schipgegevens gebruikt moeten worden om zich te abonneren op de events die betrekking hebben op dit schip.  Het is onduidelijk hoe lang het abonnement op deze schip-events in stand gehouden dient te worden.  In deze demo wordt niet verder gegaan dan het vertrekken van het schip, waarna het abonnement wordt verwijderd.

De overgang van container- naar scheeps-events introduceert een potentiële raceconditie: het is mogelijk dat het schakelen tussen abonnementen te laat plaatsvindt, waardoor het vertrek van het schip niet wordt geregistreerd.  Hoewel er in de praktijk doorgaans voldoende tijd zit tussen het laden van een container en het vertrek van het schip, is het mogelijk dat technische storing of congestie leiden tot vertraging in de ontvangst van events.  Dit probleem kan verholpen worden door bij het abonneren events die al gebeurd zijn te publiceren, zoals later in dit document beschreven.

## Robuustheid

Momenteel zijn er geen afspraken gemaakt over de te volgen procedure indien een van de beide partijen niet correct functioneert of bereikbaar is.  Dit betekent dat wanneer de webhook tijdelijk niet bereikbaar is, Portbase geen pogingen onderneemt om deze op een later tijdstip aan te roepen. Hetzelfde geldt voor het abonneren: indien dit niet succesvol is, wordt geen nieuwe poging ondernomen.

Naast het ontbreken van herhaalde pogingen bij fouten, wordt bij het abonneren geen rekening gehouden met events die reeds hebben plaatsgevonden op de container of het schip waarop wordt geabonneerd. Hierdoor kan, als gevolg van timingproblemen, belangrijke informatie verloren gaan. Het in overweging nemen van reeds voorgekomen events kan het eerder beschreven probleem met betrekking tot de overgang van container- naar scheeps-events oplossen.

## Authenticatie

Het abonneren op events geschiedt door een webhook te registreren bij het Portbase-testsysteem. Hierbij wordt gebruikgemaakt van een vooraf afgesproken API-key, in tegenstelling tot de eerdere fases van deze demo, waarin met iSHARE-authenticatie-tokens werd gewerkt. De vraag rijst of de terminal/haven partij niet zou moeten participeren in het afsprakenstelsel en zodoende de softwaretechnische mogelijkheid bieden om de bijbehorende authenticatie te implementeren.

Ook voor de aanroep van de webhook is geen authenticatie geregeld. Aangezien deze aanroep via SSL verloopt en de URL een geheim bevat, vormt dit geen direct beveiligingsprobleem. Echter, ook hier geldt dat het communicatie tussen twee partijen betreft en dat het afsprakenstelsel gebruikt kan worden om de betrouwbaarheid van de gegevens te waarborgen.

De BDI authenticatie / autorisatie aanpak is in overleg tussen de betrokken programmamanagementteam leden (programmamanagement, DIL-architect, en anchor developer) bewust buiten scope geplaatst.

## Autorisatie

Er is geen beperking op welke container- of schip-events men zich kan abonneren, waardoor de binnenkomende informatie in feite openbaar is voor iedereen die over een API-key beschikt. Deze gegevens zouden misbruikt kunnen worden door kwaadwillenden. Door gebruik te maken van het afsprakenstelsel, zoals beschreven in de sectie over authenticatie, kan dit risico worden beperkt.

Ook hier geldt dat de BDI authenticatie / autorisatie aanpak in overleg tussen de betrokken programmamanagementteam leden (programmamanagement, DIL-architect, en anchor developer) bewust buiten scope is geplaatst.

## Ontwikkelen met webhooks

Ondanks de aangeboden API voor het nabootsen van events, is softwareontwikkeling met webhooks complex omdat de ontwikkelomgeving bereikbaar moet zijn voor de partij die de aanroep naar de webhook uitvoert. Voor deze demo zijn SSH-tunnels en een Cloud VPS gebruikt, wat niet ideaal is.

Een alternatief zou kunnen zijn om de event-producerende omgeving aan te bieden als een Docker-container, zodat deze door de ontwikkelaar lokaal kan worden gedraaid.

## Vertalen van events

In fase 3 is gekozen voor EPCIS events, terwijl in deze fase door Portbase voor DCSA events is gekozen. De DCSA events worden nu vertaald naar EPCIS om ze te kunnen propageren naar andere partijen. Er is echter een semantisch verschil tussen de typen events, wat tot problemen kan leiden: EPCIS events refereren aan de start van een gebeurtenis ("departing", "arriving", "loading" etc.), terwijl DCSA events refereren aan de afronding ervan ("departed", "loaded" etc.).  Dit kan problemen opleveren bij de interpretatie van events.

# Conclusie

Het gebruik van webhooks lijkt een eenvoudige methode om een centrale event broker te vermijden. Er zijn echter enkele belangrijke aspecten die zorgvuldige overweging vereisen:

- Duidelijke afspraken over de te volgen procedure bij het (tijdelijk) niet functioneren van de producent of consument van events.

- Bij verschuiving van het onderwerp (bijvoorbeeld van container naar schip) dient te worden bepaald wie de verantwoordelijkheid draagt voor de hieruit voortvloeiende lasten m.b.t. het registreren van de relatie tussen container en schip: de producent of de consument.

- Faciliteiten ter ondersteuning van softwareontwikkeling: de mogelijkheid tot het genereren van events en het lokaal draaien van de producer.

<!-- Local Variables: -->
<!-- ispell-local-dictionary: "dutch" -->
<!-- End: -->
