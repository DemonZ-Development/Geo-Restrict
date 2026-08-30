/*
 * GeoRestrict - High-performance geographic access control.
 * Copyright (C) 2026 Demonz Development (https://demonz.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */


document.addEventListener('DOMContentLoaded', () => {
  initIcons();
  initBrand();
  initSidebar();
  initActiveNav();
  initScrollAnimations();
  initCopyButtons();
  initFaqAccordion();
  initBStatsMetrics();
});

/* ---------- Code-drawn interface icons ---------- */
function initIcons() {
  const svgNamespace = 'http://www.w3.org/2000/svg';
  const iconNamePattern = /^[a-z-]+$/;

  document.querySelectorAll('[data-icon]').forEach(host => {
    const iconName = host.dataset.icon || '';
    if (!iconNamePattern.test(iconName)) return;

    const svg = document.createElementNS(svgNamespace, 'svg');
    const use = document.createElementNS(svgNamespace, 'use');
    svg.classList.add('ui-icon');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('focusable', 'false');
    svg.setAttribute('aria-hidden', 'true');
    use.setAttribute('href', `assets/ui-icons.svg#icon-${iconName}`);
    svg.appendChild(use);
    host.replaceChildren(svg);
  });
}

/* ---------- Shared Brand ---------- */
function initBrand() {
  document.querySelectorAll('.sidebar-logo').forEach(brand => {
    if (brand.querySelector('img')) return;
    brand.innerHTML = '<img src="assets/georestrict-icon-v2.png?rev=20260715a" alt=""><b>GeoRestrict</b><span>Field guide</span>';
  });
  document.querySelector('.sidebar')?.setAttribute('aria-label', 'Documentation');
}

/* ---------- Sidebar Toggle (Mobile) ---------- */
function initSidebar() {
  const hamburger = document.querySelector('.hamburger');
  const sidebar = document.querySelector('.sidebar');
  const overlay = document.querySelector('.sidebar-overlay');

  if (!hamburger || !sidebar) return;

  hamburger.setAttribute('type', 'button');
  hamburger.setAttribute('aria-controls', sidebar.id || 'sidebar');
  hamburger.setAttribute('aria-expanded', 'false');
  hamburger.setAttribute('aria-label', 'Open documentation menu');
  overlay?.setAttribute('aria-hidden', 'true');

  function setSidebar(open) {
    hamburger.classList.toggle('active', open);
    sidebar.classList.toggle('open', open);
    overlay?.classList.toggle('active', open);
    hamburger.setAttribute('aria-expanded', String(open));
    hamburger.setAttribute('aria-label', open ? 'Close documentation menu' : 'Open documentation menu');
    overlay?.setAttribute('aria-hidden', String(!open));
    document.body.style.overflow = open ? 'hidden' : '';
  }

  function toggleSidebar() {
    setSidebar(!sidebar.classList.contains('open'));
  }

  hamburger.addEventListener('click', toggleSidebar);
  if (overlay) overlay.addEventListener('click', toggleSidebar);

  // Close on nav link click (mobile)
  sidebar.querySelectorAll('.sidebar-nav a').forEach(link => {
    link.addEventListener('click', () => {
      if (window.innerWidth <= 900 && sidebar.classList.contains('open')) {
        toggleSidebar();
      }
    });
  });

  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' && sidebar.classList.contains('open')) {
      setSidebar(false);
      hamburger.focus();
    }
  });

  window.addEventListener('resize', () => {
    if (window.innerWidth > 980 && sidebar.classList.contains('open')) setSidebar(false);
  });
}

/* ---------- Active Page Highlight ---------- */
function initActiveNav() {
  const currentPage = window.location.pathname.split('/').pop() || 'index.html';
  document.querySelectorAll('.sidebar-nav a').forEach(link => {
    const href = link.getAttribute('href');
    if (href === currentPage || (currentPage === '' && href === 'index.html')) {
      link.classList.add('active');
    } else {
      link.classList.remove('active');
    }
  });
}

/* ---------- Scroll Animations ---------- */
function initScrollAnimations() {
  const elements = document.querySelectorAll('.fade-in');
  if (!elements.length) return;

  if (!('IntersectionObserver' in window) || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
    elements.forEach(element => element.classList.add('visible'));
    return;
  }

  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach(entry => {
        if (entry.isIntersecting) {
          entry.target.classList.add('visible');
          observer.unobserve(entry.target);
        }
      });
    },
    { threshold: 0.1, rootMargin: '0px 0px -40px 0px' }
  );

  elements.forEach(el => observer.observe(el));
}

/* ---------- Copy to Clipboard ---------- */
function initCopyButtons() {
  document.querySelectorAll('.code-block-wrapper').forEach(wrapper => {
    const pre = wrapper.querySelector('pre');
    if (!pre) return;

    const btn = document.createElement('button');
    btn.className = 'copy-btn';
    btn.textContent = 'Copy';
    btn.setAttribute('aria-label', 'Copy code to clipboard');
    wrapper.appendChild(btn);

    btn.addEventListener('click', () => {
      const code = pre.querySelector('code') || pre;
      const text = code.textContent;

      navigator.clipboard.writeText(text).then(() => {
        btn.textContent = 'Copied!';
        btn.classList.add('copied');
        setTimeout(() => {
          btn.textContent = 'Copy';
          btn.classList.remove('copied');
        }, 2000);
      }).catch(() => {
        // Fallback
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
        btn.textContent = 'Copied!';
        btn.classList.add('copied');
        setTimeout(() => {
          btn.textContent = 'Copy';
          btn.classList.remove('copied');
        }, 2000);
      });
    });
  });
}

/* ---------- FAQ Accordion ---------- */
function initFaqAccordion() {
  document.querySelectorAll('.faq-question').forEach((question, index) => {
    const answer = question.parentElement.querySelector('.faq-answer');
    const answerId = answer?.id || `faq-answer-${index + 1}`;
    if (answer) {
      answer.id = answerId;
      answer.hidden = true;
      answer.setAttribute('aria-hidden', 'true');
    }
    question.setAttribute('role', 'button');
    question.setAttribute('tabindex', '0');
    question.setAttribute('aria-controls', answerId);
    question.setAttribute('aria-expanded', 'false');

    const toggle = () => {
      const item = question.parentElement;
      const isOpen = item.classList.contains('open');

      // Close all others
      document.querySelectorAll('.faq-item.open').forEach(openItem => {
        if (openItem !== item) {
          openItem.classList.remove('open');
          openItem.querySelector('.faq-question')?.setAttribute('aria-expanded', 'false');
          const openAnswer = openItem.querySelector('.faq-answer');
          if (openAnswer) {
            openAnswer.hidden = true;
            openAnswer.setAttribute('aria-hidden', 'true');
          }
        }
      });

      // Toggle current
      item.classList.toggle('open', !isOpen);
      question.setAttribute('aria-expanded', String(!isOpen));
      if (answer) {
        answer.hidden = isOpen;
        answer.setAttribute('aria-hidden', String(isOpen));
      }
    };

    question.addEventListener('click', toggle);
    question.addEventListener('keydown', event => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        toggle();
      }
    });
  });
}

/* ---------- bStats Live Indicator ---------- */
async function initBStatsMetrics() {
  const container = document.getElementById('bstats-live-metrics');
  if (!container) return;

  const plugins = [32871, 32872, 32873];
  let totalServers = 0;
  let totalPlayers = 0;

  for (const id of plugins) {
    try {
      const [sRes, pRes] = await Promise.all([
        fetch(`https://bstats.org/api/v1/plugins/${id}/charts/servers/data`),
        fetch(`https://bstats.org/api/v1/plugins/${id}/charts/players/data`)
      ]);
      if (sRes.ok) {
        const sData = await sRes.json();
        if (Array.isArray(sData) && sData.length > 0) {
          totalServers += sData[sData.length - 1][1] || 0;
        }
      }
      if (pRes.ok) {
        const pData = await pRes.json();
        if (Array.isArray(pData) && pData.length > 0) {
          totalPlayers += pData[pData.length - 1][1] || 0;
        }
      }
    } catch (e) {
      // Ignore network errors on metrics lookup
    }
  }

  container.innerHTML = `
    <div class="bstats-live-pill">
      <span class="bstats-dot"></span>
      <span><strong>bStats Live:</strong> ${totalServers} Active Servers &bull; ${totalPlayers} Players Online</span>
    </div>
  `;
}
