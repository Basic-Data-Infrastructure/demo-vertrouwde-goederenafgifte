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

  /* Show busy state when any form in the dialog is submitted or
   * cancel button clicked. */
  const addOnclickBusy = (dialog) => {
    dialog.querySelectorAll('form').forEach(form => {
      form.addEventListener('submit', ev => {
        addClassName(dialog, 'fx-busy')
      })

      dialog.querySelectorAll('.dialog-close, .button.cancel').forEach(el => {
        el.addEventListener('click', ev => {
          addClassName(dialog, 'fx-busy')
        })
      })
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
      addOnclickBusy(dialog)
    } else {
      // something went wrong, replace the entire document by the loaded page
      document.documentElement.replaceChildren(
        ...page.documentElement.children
      )
    }
  }

  /* Show modal dialog and close all others. */
  const showModal = (dialog) => {
    document.querySelectorAll('dialog').forEach(x => x.close())
    dialog.showModal()
  }

  /* Setup anchor to open `href` on click in the `dialog#fx-dialog`
   * element. */
  const setupFxDialogAnchor = (anchor) => {
    const dialog = document.querySelector(anchor.getAttribute('fx-dialog'))

    anchor.addEventListener('click', async function (ev) {
      ev.preventDefault()

      showModal(dialog)
      addClassName(dialog, 'fx-busy')

      const url = anchor.getAttribute('href')
      const page = await fetchPage(url)

      replaceDialogPage(dialog, page)
      removeClassName(dialog, 'fx-busy')

      window.history.pushState({ dialog: true }, '', url)
    })
  }

  /* Setup `form` to load submit response inside the
   * `dialog#fx-dialog` element. */
  const setupFxDialogForm = (form) => {
    const dialog = document.querySelector(form.getAttribute('fx-dialog'))

    form.addEventListener('submit', async function (ev) {
      ev.preventDefault()

      showModal(dialog)
      addClassName(dialog, 'fx-busy')

      const page = await fetchPage(form.getAttribute('action') || '', {
        method: form.getAttribute('method'),
        body: new FormData(form)
      })

      replaceDialogPage(dialog, page)
      removeClassName(dialog, 'fx-busy')
    })
  }

  /* Decorate all fx annotated elements of `target`. */
  const onLoad = ({ target }) => {
    target.querySelectorAll('a[fx-dialog]').forEach(setupFxDialogAnchor)
    target.querySelectorAll('form[fx-dialog]').forEach(setupFxDialogForm)
  }

  // Show busy state when dialog-close link clicked.
  window.addEventListener('load', ev => {
    document.querySelectorAll('dialog').forEach(addOnclickBusy)
  })

  // Navigated back to a "real" page.
  window.addEventListener('popstate', ev => {
    if (!window.history.state) {
      window.navigation.reload()
    }
  })

  // Setup fx stuff when page loaded.
  document.addEventListener('DOMContentLoaded', onLoad)
})()
