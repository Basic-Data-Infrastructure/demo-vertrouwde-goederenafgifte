/* SPDX-FileCopyrightText: 2024 Jomco B.V.
 * SPDX-FileCopyrightText: 2024 Topsector Logistiek
 * SPDX-FileContributor: Joost Diepenmaat <joost@jomco.nl>
 * SPDX-FileContributor: Remco van 't Veer <remco@jomco.nl>
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
(function () {
  const addClassName = (el, cn) => {
    const cns = (
      el.getAttribute('class') || ''
    ).split(/\s+/).filter(s => s.length && s !== cn)

    el.setAttribute('class', (cns.concat([cn])).join(' '))
  }

  const removeClassName = (el, cn) => {
    const cns = (
      el.getAttribute('class') || ''
    ).split(/\s+/).filter(s => s.length && s !== cn)

    if (cn.length) {
      el.setAttribute('class', cns.join(' '))
    } else {
      el.removeAttribute('class')
    }
  }

  /* Run `fetch` using `args` and return a DOM element containing the
   * HTML response. */
  const fetchPage = async function (url, opts = {}) {
    if (!opts.headers) opts.headers = {}
    opts.headers.accept = 'text/html'

    const res = await fetch(url, opts)
    const html = await res.text()

    const parser = new DOMParser() // eslint-disable-line no-undef
    return parser.parseFromString(html, 'text/html')
  }

  /* Setup `form` to load submit response inside the
   * `dialog#fx-dialog` element. */
  const setupDialogForm = (dialog, form) => {
    form.addEventListener('submit', async function (ev) {
      ev.preventDefault()
      addClassName(form, 'fx-busy')

      const page = await fetchPage(form.getAttribute('action') || '', {
        method: form.getAttribute('method'),
        body: new FormData(form)
      })
      replaceDialogPage(dialog, page)

      removeClassName(form, 'fx-busy')
    })
  }

  /* Use `page` to populate the `dialog#fx-dialog` element in the DOM
   * and show it. */
  const replaceDialogPage = (dialog, page) => {
    const newHeader = page.querySelector('header.container')
    const newMain = page.querySelector('main.container')

    if (newHeader && newMain) {
      const oldHeader = dialog.querySelector('header')
      oldHeader.parentElement.replaceChild(newHeader, oldHeader)
      const oldMain = dialog.querySelector('main')
      oldMain.parentElement.replaceChild(newMain, oldMain)

      onLoad({ target: newMain })
      dialog.querySelectorAll('form[fx-dialog]').forEach(
        form => {
          const dialog = document.querySelector(form.getAttribute('fx-dialog'))
          setupDialogForm(dialog, form)
        }
      )

      dialog.showModal()
    } else {
      // something went wrong, replace the entire document by the loaded page
      document.documentElement.replaceChildren(
        ...page.documentElement.children
      )
    }
  }

  /* Setup anchor to open `href` on click in the `dialog#fx-dialog`
   * element. */
  const setupFxDialog = el => {
    const dialog = document.querySelector(el.getAttribute('fx-dialog'))

    el.addEventListener('click', async function (ev) {
      ev.preventDefault()
      addClassName(el, 'fx-busy')

      const url = el.getAttribute('href')
      const page = await fetchPage(url)
      replaceDialogPage(dialog, page)

      window.history.pushState({ dialog: true }, '', url)
      removeClassName(el, 'fx-busy')
    })
  }

  /* Decorate all fx annotated elements of `target`. */
  const onLoad = ({ target }) => {
    target.querySelectorAll('a[fx-dialog]').forEach(setupFxDialog)
  }

  window.addEventListener('load', onLoad)

  window.addEventListener('popstate', ev => {
    if (!window.history.state) {
      // navigated back to a "real" page
      window.navigation.reload()
    }
    console.log(window.history.state)
  })
})()
