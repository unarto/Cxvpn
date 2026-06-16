/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

export default function App() {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-zinc-950 font-sans text-zinc-100">
      <div className="w-full max-w-sm rounded-2xl border border-zinc-800 bg-zinc-900 p-8 text-center shadow-2xl">
        <div className="mx-auto mb-6 flex h-16 w-16 items-center justify-center rounded-full bg-blue-600 shadow-[0_0_15px_rgba(37,99,235,0.5)]">
          <svg xmlns="http://www.w3.org/2000/svg" className="h-8 w-8 text-white" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 15v2m-6 4h12a2 2 0 002-2v-6a2 2 0 00-2-2H6a2 2 0 00-2 2v6a2 2 0 002 2zm10-10V7a4 4 0 00-8 0v4h8z" />
          </svg>
        </div>
        <h1 className="mb-2 text-3xl font-bold tracking-tight">Cvpn</h1>
        <p className="mb-8 text-sm text-zinc-400">
          Proyek telah disiapkan tanpa penambahan file. Silakan unggah file source code Anda pada panel editor untuk melanjutkan.
        </p>
        <div className="flex animate-pulse justify-center space-x-2">
          <div className="h-2 w-2 rounded-full bg-blue-500"></div>
          <div className="h-2 w-2 rounded-full bg-blue-500 delay-75"></div>
          <div className="h-2 w-2 rounded-full bg-blue-500 delay-150"></div>
        </div>
      </div>
    </div>
  );
}
