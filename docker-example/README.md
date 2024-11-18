<!--
SPDX-FileCopyrightText: 2024 Jomco B.V.
SPDX-FileCopyrightText: 2024 Topsector Logistiek
SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>

SPDX-License-Identifier: AGPL-3.0-or-later
-->

This is an example docker configuration using [the BDI
Stack](https://github.com/Basic-Data-Infrastructure/bdi-stack) for a
local Association Register and Authorization Register.

The event bus (Pulsar) is disabled for now.

First create the local certificates:

```sh
make config
```

Then start the environment using

```sh
docker-compose up
```

This will create a docker image for the VGU demo and start the
container, connected to the local BDI stack in separate containers.

To connect to the UI, open http://localhost:9009/



